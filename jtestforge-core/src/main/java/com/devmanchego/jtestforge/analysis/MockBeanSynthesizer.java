package com.devmanchego.jtestforge.analysis;

import com.devmanchego.jtestforge.model.MockBeanDeclaration;
import com.devmanchego.jtestforge.util.AtomicFileWriter;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Adds mock-bean field declarations to an existing Spring slice test class —
 * jtestforge-specification.md §7.6's escalation path.
 *
 * <p>The one place a generated candidate is allowed to change a test class's Spring
 * context cache key. Everywhere else (§7.6, {@link ContextKeyGuard}) a candidate that
 * needs a mock bean the class does not yet declare is refused; this class is what turns
 * that refusal into forward progress, applied <b>once</b> per class (the caller enforces
 * that via {@code MockBeanEscalation}) as a single deliberate edit rather than growing the
 * mock-bean set one generated test at a time.
 *
 * <p>Fields already present are skipped rather than duplicated, so calling this twice with
 * an overlapping set is safe.
 */
public final class MockBeanSynthesizer {

    private final JavaParser javaParser = TestClassEditing.newParser();

    /**
     * @param mockBeanAnnotationFqn the annotation to declare each field with -
     *                              version-sensitive (§7.3), so it always comes from the
     *                              target module's own detected facts, never hard-coded
     * @return the field names actually added; empty if every requested bean was already
     *         declared
     */
    public List<String> synthesize(
            Path testFile, List<MockBeanDeclaration> mockBeans, String mockBeanAnnotationFqn) {
        Optional<String> source = TestClassEditing.readSource(testFile);
        if (source.isEmpty()) {
            return List.of();
        }
        Optional<CompilationUnit> parsed = TestClassEditing.parse(javaParser, source.get());
        if (parsed.isEmpty()) {
            return List.of();
        }
        CompilationUnit compilationUnit = parsed.get();
        Optional<ClassOrInterfaceDeclaration> topLevel = TestClassEditing.topLevelClassOf(compilationUnit);
        if (topLevel.isEmpty()) {
            return List.of();
        }
        ClassOrInterfaceDeclaration testClass = topLevel.get();

        List<MockBeanDeclaration> toAdd = mockBeans.stream()
                .filter(bean -> !alreadyDeclared(testClass, bean.name()))
                .toList();
        if (toAdd.isEmpty()) {
            return List.of();
        }

        SourceTextEditor editor = new SourceTextEditor(source.get());
        insertFields(editor, testClass, toAdd, mockBeanAnnotationFqn);
        insertMissingImports(editor, compilationUnit, toAdd, mockBeanAnnotationFqn);

        write(testFile, editor.apply());
        return toAdd.stream().map(MockBeanDeclaration::name).toList();
    }

    private boolean alreadyDeclared(ClassOrInterfaceDeclaration testClass, String fieldName) {
        return testClass.getFields().stream()
                .flatMap(field -> field.getVariables().stream())
                .anyMatch(variable -> variable.getNameAsString().equals(fieldName));
    }

    private void insertFields(SourceTextEditor editor, ClassOrInterfaceDeclaration testClass,
                              List<MockBeanDeclaration> toAdd, String mockBeanAnnotationFqn) {
        int closingBraceOffset = editor.offsetOf(testClass.getRange().orElseThrow().end);
        int insertionOffset = editor.startOfLineContaining(closingBraceOffset);
        String indent = memberIndentOf(editor, testClass);
        String annotationSimpleName = simpleNameOf(mockBeanAnnotationFqn);

        StringBuilder block = new StringBuilder();
        for (MockBeanDeclaration bean : toAdd) {
            block.append('\n').append(indent).append('@').append(annotationSimpleName).append('\n')
                    .append(indent).append("private ").append(bean.simpleTypeName())
                    .append(' ').append(bean.name()).append(";\n");
        }
        editor.insert(insertionOffset, block.toString());
    }

    /** Indentation of the class's first existing member, falling back to four spaces. */
    private String memberIndentOf(SourceTextEditor editor, ClassOrInterfaceDeclaration testClass) {
        return testClass.getMembers().stream()
                .findFirst()
                .flatMap(member -> member.getRange().map(range -> range.begin))
                .map(position -> editor.indentationOfLineContaining(editor.offsetOf(position)))
                .filter(found -> !found.isEmpty())
                .orElse("    ");
    }

    private void insertMissingImports(SourceTextEditor editor, CompilationUnit compilationUnit,
                                      List<MockBeanDeclaration> toAdd, String mockBeanAnnotationFqn) {
        Set<String> alreadyImported = new LinkedHashSet<>();
        for (ImportDeclaration existing : compilationUnit.getImports()) {
            alreadyImported.add(existing.getNameAsString());
        }

        List<String> required = new ArrayList<>();
        required.add(mockBeanAnnotationFqn);
        for (MockBeanDeclaration bean : toAdd) {
            if (isImportable(bean.typeName())) {
                required.add(bean.typeName());
            }
        }
        for (String importedType : required) {
            if (!alreadyImported.add(importedType)) {
                continue;
            }
            editor.insert(importInsertionOffset(editor, compilationUnit, importedType),
                    "import " + importedType + ";\n");
        }
    }

    private int importInsertionOffset(
            SourceTextEditor editor, CompilationUnit compilationUnit, String newImport) {
        List<ImportDeclaration> imports = compilationUnit.getImports();
        for (ImportDeclaration existing : imports) {
            if (existing.isStatic() || existing.getNameAsString().compareTo(newImport) <= 0) {
                continue;
            }
            return editor.startOfLineContaining(editor.offsetOf(existing.getRange().orElseThrow().begin));
        }
        if (!imports.isEmpty()) {
            ImportDeclaration last = imports.get(imports.size() - 1);
            return editor.pastEndOfLineContaining(editor.offsetOf(last.getRange().orElseThrow().end));
        }
        return compilationUnit.getPackageDeclaration()
                .map(declaration -> editor.pastEndOfLineContaining(
                        editor.offsetOf(declaration.getRange().orElseThrow().end)))
                .orElse(0);
    }

    /** Only a fully-qualified, non-{@code java.lang} type can be imported. */
    private boolean isImportable(String typeName) {
        return typeName.contains(".") && !typeName.startsWith("java.lang.");
    }

    private String simpleNameOf(String fqn) {
        return fqn.substring(fqn.lastIndexOf('.') + 1);
    }

    private void write(Path testFile, String content) {
        try {
            AtomicFileWriter.write(testFile, content);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to synthesise mock beans into " + testFile, e);
        }
    }
}
