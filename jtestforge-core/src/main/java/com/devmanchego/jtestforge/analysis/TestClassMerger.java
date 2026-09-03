package com.devmanchego.jtestforge.analysis;

import com.devmanchego.jtestforge.model.TestCandidate;
import com.devmanchego.jtestforge.util.AtomicFileWriter;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Inserts generated test methods into an existing test class —
 * jtestforge-specification.md §6.2, §7.2.
 *
 * <p>Two properties this class must never violate:
 *
 * <ul>
 *   <li><b>Everything hand-written survives untouched.</b> Only new methods and new
 *       imports are added; existing members, formatting, comments and import order are
 *       left exactly as found, because the edit is a text splice at AST offsets rather
 *       than a reprint of the file (see {@link SourceTextEditor}).</li>
 *   <li><b>All-or-nothing.</b> If any candidate in a batch is refused, nothing is written.
 *       A partially applied batch would leave the unit's bookkeeping disagreeing with the
 *       file, which resume reconciliation (§8.2.3) would then have to guess at.</li>
 * </ul>
 *
 * <p>Each method is inserted as the block {@code "\n" + indented method + "\n"} placed
 * immediately before the class's closing brace, and each import as one whole line. Both
 * shapes are exactly what {@link TestClassReverter} knows how to remove again.
 */
public final class TestClassMerger {

    private static final String DEFAULT_INDENT = "    ";

    private final JavaParser javaParser = TestClassEditing.newParser();

    public MergeResult merge(Path testFile, List<TestCandidate> candidates) {
        Optional<String> source = TestClassEditing.readSource(testFile);
        if (source.isEmpty()) {
            return MergeResult.rejected("test file does not exist or could not be read: " + testFile);
        }
        Optional<CompilationUnit> parsed = TestClassEditing.parse(javaParser, source.get());
        if (parsed.isEmpty()) {
            return MergeResult.rejected("test file could not be parsed: " + testFile);
        }
        CompilationUnit compilationUnit = parsed.get();
        Optional<ClassOrInterfaceDeclaration> topLevel = TestClassEditing.topLevelClassOf(compilationUnit);
        if (topLevel.isEmpty()) {
            return MergeResult.rejected("no top-level class found in " + testFile);
        }
        ClassOrInterfaceDeclaration testClass = topLevel.get();

        List<MethodDeclaration> parsedCandidates = new ArrayList<>();
        for (TestCandidate candidate : candidates) {
            Optional<MethodDeclaration> method = parseCandidate(candidate);
            if (method.isEmpty()) {
                return MergeResult.rejected(
                        "candidate '" + candidate.methodName() + "' is not a parseable method declaration");
            }
            Optional<String> rejection = reasonToRefuse(method.get(), compilationUnit, testClass);
            if (rejection.isPresent()) {
                return MergeResult.rejected(rejection.get());
            }
            parsedCandidates.add(method.get());
        }

        SourceTextEditor editor = new SourceTextEditor(source.get());
        List<String> addedTestNames = insertMethods(editor, testClass, parsedCandidates);
        List<String> addedImports = insertMissingImports(editor, compilationUnit, candidates);

        if (editor.hasEdits()) {
            write(testFile, editor.apply());
        }
        return MergeResult.merged(addedTestNames, addedImports);
    }

    private List<String> insertMethods(
            SourceTextEditor editor, ClassOrInterfaceDeclaration testClass, List<MethodDeclaration> methods) {
        if (methods.isEmpty()) {
            return List.of();
        }
        int closingBraceOffset = editor.offsetOf(testClass.getRange().orElseThrow().end);
        int insertionOffset = editor.startOfLineContaining(closingBraceOffset);
        String indent = memberIndentOf(editor, testClass);

        StringBuilder block = new StringBuilder();
        List<String> addedNames = new ArrayList<>();
        for (MethodDeclaration method : methods) {
            block.append('\n').append(indent(method.toString(), indent)).append('\n');
            addedNames.add(method.getNameAsString());
        }
        editor.insert(insertionOffset, block.toString());
        return addedNames;
    }

    /** Indentation of the class's first existing member, falling back to four spaces. */
    private String memberIndentOf(SourceTextEditor editor, ClassOrInterfaceDeclaration testClass) {
        return testClass.getMembers().stream()
                .findFirst()
                .flatMap(member -> member.getRange().map(range -> range.begin))
                .map(position -> editor.indentationOfLineContaining(editor.offsetOf(position)))
                .filter(found -> !found.isEmpty())
                .orElse(DEFAULT_INDENT);
    }

    private String indent(String methodSource, String indent) {
        return methodSource.lines()
                .map(line -> line.isBlank() ? line : indent + line)
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    private Optional<String> reasonToRefuse(
            MethodDeclaration candidate, CompilationUnit compilationUnit, ClassOrInterfaceDeclaration testClass) {
        String name = candidate.getNameAsString();
        boolean nameAlreadyUsed = compilationUnit.findAll(MethodDeclaration.class).stream()
                .anyMatch(existing -> existing.getNameAsString().equals(name));
        if (nameAlreadyUsed) {
            return Optional.of("a method named '" + name + "' already exists in this test class");
        }
        return shadowedFieldName(candidate, testClass)
                .map(fieldName -> "candidate '" + name + "' declares a local variable '" + fieldName
                        + "' that shadows a field of the same name; a test that shadows its own mock "
                        + "exercises a second, uninjected instance and passes while proving nothing");
    }

    /**
     * A local variable shadowing a mock field is refused because the resulting test reads
     * as correct while testing nothing: {@code PaymentGateway gateway = mock(...)} inside a
     * class that already has an {@code @Mock PaymentGateway gateway} creates a second mock
     * which is never injected into the subject.
     */
    private Optional<String> shadowedFieldName(
            MethodDeclaration candidate, ClassOrInterfaceDeclaration testClass) {
        Set<String> fieldNames = new LinkedHashSet<>();
        for (FieldDeclaration field : testClass.getFields()) {
            for (VariableDeclarator variable : field.getVariables()) {
                fieldNames.add(variable.getNameAsString());
            }
        }
        return candidate.findAll(VariableDeclarationExpr.class).stream()
                .flatMap(declaration -> declaration.getVariables().stream())
                .map(VariableDeclarator::getNameAsString)
                .filter(fieldNames::contains)
                .findFirst();
    }

    /**
     * Adds only imports the file does not already have, each as one whole line at its
     * sorted position among the existing non-static imports.
     *
     * <p>The existing list is never re-sorted. Reordering a developer's imports is a change
     * to their file that no generation attempt earned the right to make, and it would also
     * leave a subsequent revert unable to restore the original bytes.
     */
    private List<String> insertMissingImports(
            SourceTextEditor editor, CompilationUnit compilationUnit, List<TestCandidate> candidates) {
        Set<String> alreadyImported = new LinkedHashSet<>();
        for (ImportDeclaration existing : compilationUnit.getImports()) {
            alreadyImported.add(existing.getNameAsString());
        }

        List<String> added = new ArrayList<>();
        for (TestCandidate candidate : candidates) {
            for (String required : candidate.requiredImports()) {
                if (!alreadyImported.add(required)) {
                    continue;
                }
                editor.insert(importInsertionOffset(editor, compilationUnit, required),
                        "import " + required + ";\n");
                added.add(required);
            }
        }
        return added;
    }

    private int importInsertionOffset(
            SourceTextEditor editor, CompilationUnit compilationUnit, String newImport) {
        List<ImportDeclaration> imports = compilationUnit.getImports();
        for (ImportDeclaration existing : imports) {
            if (existing.isStatic() || existing.getNameAsString().compareTo(newImport) <= 0) {
                continue;
            }
            return editor.startOfLineContaining(
                    editor.offsetOf(existing.getRange().orElseThrow().begin));
        }
        if (!imports.isEmpty()) {
            ImportDeclaration last = imports.get(imports.size() - 1);
            return editor.pastEndOfLineContaining(editor.offsetOf(last.getRange().orElseThrow().end));
        }
        // No imports at all: place it directly after the package declaration, with no
        // added blank line. A blank line would have to be removed again on revert, and
        // deciding whether a blank line "belongs" to the import or to the package
        // declaration cannot be done reliably - so none is introduced.
        return compilationUnit.getPackageDeclaration()
                .map(declaration -> editor.pastEndOfLineContaining(
                        editor.offsetOf(declaration.getRange().orElseThrow().end)))
                .orElse(0);
    }

    private Optional<MethodDeclaration> parseCandidate(TestCandidate candidate) {
        ParseResult<BodyDeclaration<?>> result = javaParser.parseBodyDeclaration(candidate.sourceCode());
        return result.getResult()
                .filter(BodyDeclaration::isMethodDeclaration)
                .map(BodyDeclaration::asMethodDeclaration);
    }

    private void write(Path testFile, String content) {
        try {
            AtomicFileWriter.write(testFile, content);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write merged test class to " + testFile, e);
        }
    }
}
