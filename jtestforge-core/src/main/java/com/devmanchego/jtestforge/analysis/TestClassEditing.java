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

    /**
     * The file's text in {@code charset}, or empty if it is missing or not valid in that encoding.
     * Strict on purpose: the lenient default would swap undecodable bytes for U+FFFD, and the edit
     * that follows rewrites the whole file - so the developer's accents would be replaced for good.
     */
    static Optional<String> readSource(Path testFile, java.nio.charset.Charset charset) {
        if (!Files.isRegularFile(testFile)) {
            return Optional.empty();
        }
        try {
            return Optional.of(charset.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(Files.readAllBytes(testFile))).toString());
        } catch (IOException e) {
            // includes CharacterCodingException
            return Optional.empty();
        }
    }

    private static final String STATIC_PREFIX = "static ";

    /**
     * The name an import is compared by. A candidate's import arrives as {@code static a.B.c} for a
     * static import - the keyword kept, since the merger needs it to write the line - while
     * JavaParser's {@code getNameAsString()} of the same declaration is {@code a.B.c}. Compared
     * raw, the merger did not recognise an import the file already had (and added it a second time)
     * and the reverter never found a static import it had added (and left it behind).
     */
    static String importKey(String requiredImport) {
        return requiredImport.startsWith(STATIC_PREFIX)
                ? requiredImport.substring(STATIC_PREFIX.length()).strip()
                : requiredImport;
    }

    static boolean isStaticImport(String requiredImport) {
        return requiredImport.startsWith(STATIC_PREFIX);
    }

    /** Whether every character of {@code text} can be written in {@code charset}. */
    static boolean canEncode(java.nio.charset.Charset charset, String text) {
        return charset.newEncoder().canEncode(text);
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
