package com.devmanchego.jtestforge.analysis;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AnnotationFqnResolverTest {

    @Test
    void resolvesASimpleNameUsingTheMatchingImport() {
        AnnotationExpr annotation = classAnnotation("""
                import jakarta.persistence.Entity;
                @Entity
                class Foo {}
                """);

        assertThat(AnnotationFqnResolver.resolve(annotation, annotation.findCompilationUnit().orElseThrow()))
                .isEqualTo("jakarta.persistence.Entity");
    }

    @Test
    void returnsTheWrittenValueUnchangedWhenAlreadyQualified() {
        AnnotationExpr annotation = classAnnotation("""
                @jakarta.persistence.Entity
                class Foo {}
                """);

        assertThat(AnnotationFqnResolver.resolve(annotation, annotation.findCompilationUnit().orElseThrow()))
                .isEqualTo("jakarta.persistence.Entity");
    }

    @Test
    void fallsBackToTheBareSimpleNameWhenNoImportMatches() {
        // Same-package annotations, and java.lang annotations, have no import to find -
        // the bare name is the correct answer for those too.
        AnnotationExpr annotation = classAnnotation("""
                @Deprecated
                class Foo {}
                """);

        assertThat(AnnotationFqnResolver.resolve(annotation, annotation.findCompilationUnit().orElseThrow()))
                .isEqualTo("Deprecated");
    }

    @Test
    void ignoresWildcardImportsRatherThanMisresolvingThroughThem() {
        AnnotationExpr annotation = classAnnotation("""
                import jakarta.persistence.*;
                @Entity
                class Foo {}
                """);

        // A wildcard import cannot tell us the FQN without a real classpath - the bare
        // name is the honest "best effort" answer here, not a guessed package.
        assertThat(AnnotationFqnResolver.resolve(annotation, annotation.findCompilationUnit().orElseThrow()))
                .isEqualTo("Entity");
    }

    @Test
    void ignoresStaticImportsWhenLookingForAnAnnotationType() {
        AnnotationExpr annotation = classAnnotation("""
                import static java.util.Collections.emptyList;
                import jakarta.persistence.Entity;
                @Entity
                class Foo {}
                """);

        assertThat(AnnotationFqnResolver.resolve(annotation, annotation.findCompilationUnit().orElseThrow()))
                .isEqualTo("jakarta.persistence.Entity");
    }

    private AnnotationExpr classAnnotation(String source) {
        CompilationUnit compilationUnit = StaticJavaParser.parse(source);
        ClassOrInterfaceDeclaration declaration = compilationUnit.findFirst(ClassOrInterfaceDeclaration.class)
                .orElseThrow();
        return declaration.getAnnotations().get(0);
    }
}
