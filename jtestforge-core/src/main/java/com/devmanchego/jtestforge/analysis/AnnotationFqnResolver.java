package com.devmanchego.jtestforge.analysis;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.Name;

import java.util.List;
import java.util.Optional;

/**
 * Best-effort resolution of an annotation usage to its fully-qualified name, using only
 * the compilation unit's own import declarations - deliberately not the symbol solver.
 *
 * <p>{@code excludeAnnotations} (jtestforge-specification.md §5, §7.1) must correctly
 * recognise annotations like {@code jakarta.persistence.Entity} or Spring's
 * {@code @Component} family on a target module JTestForge does not itself depend on and
 * will never be able to fully classpath-resolve. Import-based resolution needs nothing
 * but the source text, so it works identically whether or not those annotation types are
 * resolvable - which for JTestForge's own build, they never are.
 */
final class AnnotationFqnResolver {

    private AnnotationFqnResolver() {
    }

    /**
     * @return the annotation's best-effort FQN: the value as written if it already looks
     * qualified, the matching import's FQN if one is found, or the as-written (simple)
     * name as a last resort - same-package and {@code java.lang} annotations have no
     * import to match against, and a bare name is the correct answer for those too.
     */
    static String resolve(AnnotationExpr annotation, CompilationUnit compilationUnit) {
        String written = annotation.getNameAsString();
        if (written.contains(".")) {
            return written;
        }
        return findImportFor(written, compilationUnit).orElse(written);
    }

    private static Optional<String> findImportFor(String simpleName, CompilationUnit compilationUnit) {
        List<ImportDeclaration> imports = compilationUnit.getImports();
        for (ImportDeclaration importDeclaration : imports) {
            if (importDeclaration.isAsterisk() || importDeclaration.isStatic()) {
                continue;
            }
            Name name = importDeclaration.getName();
            if (name.getIdentifier().equals(simpleName)) {
                return Optional.of(name.asString());
            }
        }
        return Optional.empty();
    }
}
