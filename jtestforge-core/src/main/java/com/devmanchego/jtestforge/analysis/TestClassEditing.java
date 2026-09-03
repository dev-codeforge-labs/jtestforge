package com.devmanchego.jtestforge.analysis;

import com.github.javaparser.JavaParser;
import com.github.javaparser.Position;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/** Shared plumbing for the two components that write into a developer's test file. */
final class TestClassEditing {

    private TestClassEditing() {
    }

    static JavaParser newParser() {
        return new JavaParser(new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21));
    }

    static Optional<String> readSource(Path testFile) {
        if (!Files.isRegularFile(testFile)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Files.readString(testFile));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    static Optional<CompilationUnit> parse(JavaParser parser, String source) {
        return parser.parse(source).getResult();
    }

    static Optional<ClassOrInterfaceDeclaration> topLevelClassOf(CompilationUnit compilationUnit) {
        return compilationUnit.getTypes().stream()
                .filter(ClassOrInterfaceDeclaration.class::isInstance)
                .map(ClassOrInterfaceDeclaration.class::cast)
                .findFirst();
    }

    /**
     * The earliest source position belonging to {@code node}, including any attached
     * comment.
     *
     * <p>A node's own range starts at its first token, which for a method is its first
     * annotation - but a preceding Javadoc or line comment is modelled as an attached
     * comment outside that range. Removing the declaration without it would leave the
     * comment stranded above whatever follows.
     */
    static Position startIncludingComment(Node node) {
        Position ownStart = node.getRange().orElseThrow().begin;
        return node.getComment()
                .flatMap(comment -> comment.getRange().map(range -> range.begin))
                .filter(commentStart -> commentStart.isBefore(ownStart))
                .orElse(ownStart);
    }
}
