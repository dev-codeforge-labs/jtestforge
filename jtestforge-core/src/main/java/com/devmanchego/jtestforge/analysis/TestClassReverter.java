package com.devmanchego.jtestforge.analysis;

import com.devmanchego.jtestforge.util.AtomicFileWriter;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Removes exactly what one work unit added to a test class —
 * jtestforge-specification.md §9, "Revert semantics".
 *
 * <p>By subtraction, deliberately <b>not</b> by restoring the backup. Several units write
 * into the same test class over a run; restoring a backup to undo the third unit would
 * silently discard the first two units' successful work as well. The backup exists as a
 * last-resort recovery artifact, not as the revert mechanism.
 *
 * <p>Removal mirrors {@link TestClassMerger}'s insertion exactly: a method is removed
 * together with the single blank line that preceded it and its own trailing newline, and
 * an import as one whole line. That symmetry is what makes merge-then-revert restore the
 * developer's file byte for byte.
 *
 * <p>Only imports the unit itself introduced are candidates for removal, and only when
 * nothing left in the file still refers to them. Pruning every unreferenced import would
 * quietly tidy imports the developer chose to leave unused - editing a file this unit was
 * never asked to touch.
 */
public final class TestClassReverter {

    private final JavaParser javaParser = TestClassEditing.newParser();

    public RevertResult revert(Path testFile, Collection<String> methodNames, Collection<String> importsAdded) {
        Optional<String> source = TestClassEditing.readSource(testFile);
        if (source.isEmpty()) {
            return new RevertResult(List.of(), List.of());
        }
        Optional<CompilationUnit> parsed = TestClassEditing.parse(javaParser, source.get());
        if (parsed.isEmpty() || TestClassEditing.topLevelClassOf(parsed.get()).isEmpty()) {
            return new RevertResult(List.of(), List.of());
        }
        CompilationUnit compilationUnit = parsed.get();
        ClassOrInterfaceDeclaration testClass = TestClassEditing.topLevelClassOf(compilationUnit).orElseThrow();

        SourceTextEditor editor = new SourceTextEditor(source.get());
        List<String> removedMethods = removeMethods(editor, compilationUnit, Set.copyOf(methodNames));
        List<String> removedImports = removeNowUnusedImports(
                editor, compilationUnit, testClass, importsAdded, Set.copyOf(methodNames));

        if (!editor.hasEdits()) {
            // Nothing to undo - leave the file completely alone rather than rewriting it
            // with identical content and disturbing its modification time.
            return new RevertResult(List.of(), List.of());
        }
        write(testFile, editor.apply());
        return new RevertResult(removedMethods, removedImports);
    }

    private List<String> removeMethods(
            SourceTextEditor editor, CompilationUnit compilationUnit, Set<String> methodNames) {
        List<String> removed = new ArrayList<>();
        for (MethodDeclaration method : compilationUnit.findAll(MethodDeclaration.class)) {
            if (!methodNames.contains(method.getNameAsString())) {
                continue;
            }
            int declarationStart = editor.offsetOf(TestClassEditing.startIncludingComment(method));
            int start = editor.startOfLineContaining(declarationStart);
            // The merger writes each method preceded by exactly one blank line; taking it
            // back is what closes the gap the method leaves behind.
            if (editor.isPrecededByBlankLine(start)) {
                start = editor.startOfLineContaining(start - 1);
            }
            int end = editor.pastEndOfLineContaining(
                    editor.offsetOf(method.getRange().orElseThrow().end));
            editor.delete(start, end);
            removed.add(method.getNameAsString());
        }
        return removed;
    }

    private List<String> removeNowUnusedImports(
            SourceTextEditor editor, CompilationUnit compilationUnit, ClassOrInterfaceDeclaration testClass,
            Collection<String> importsAdded, Set<String> removedMethodNames) {
        if (importsAdded.isEmpty()) {
            return List.of();
        }
        String remainingSource = sourceWithoutMethods(testClass, removedMethodNames);
        List<String> removed = new ArrayList<>();

        for (ImportDeclaration existing : compilationUnit.getImports()) {
            String importedName = existing.getNameAsString();
            if (!importsAdded.contains(importedName)) {
                continue;
            }
            if (isReferencedIn(remainingSource, simpleNameOf(importedName))) {
                continue;
            }
            int start = editor.startOfLineContaining(
                    editor.offsetOf(existing.getRange().orElseThrow().begin));
            int end = editor.pastEndOfLineContaining(
                    editor.offsetOf(existing.getRange().orElseThrow().end));
            editor.delete(start, end);
            removed.add(importedName);
        }
        return removed;
    }

    /**
     * The class body as it will read once the reverted methods are gone.
     *
     * <p>Judging import usage against the file as it currently stands would keep every
     * import the reverted method itself was the only user of - which is precisely the set
     * that should go.
     */
    private String sourceWithoutMethods(ClassOrInterfaceDeclaration testClass, Set<String> removedMethodNames) {
        ClassOrInterfaceDeclaration copy = testClass.clone();
        for (MethodDeclaration method : List.copyOf(copy.findAll(MethodDeclaration.class))) {
            if (removedMethodNames.contains(method.getNameAsString())) {
                method.remove();
            }
        }
        return copy.toString();
    }

    /** Word-boundary match, so removing {@code java.util.List} is not blocked by {@code Lists}. */
    private boolean isReferencedIn(String source, String simpleName) {
        return Pattern.compile("\\b" + Pattern.quote(simpleName) + "\\b").matcher(source).find();
    }

    private String simpleNameOf(String fqn) {
        int lastDot = fqn.lastIndexOf('.');
        return lastDot < 0 ? fqn : fqn.substring(lastDot + 1);
    }

    private void write(Path testFile, String content) {
        try {
            AtomicFileWriter.write(testFile, content);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write reverted test class to " + testFile, e);
        }
    }
}
