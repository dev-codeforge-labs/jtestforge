package com.devmanchego.jtestforge.spring;

import com.devmanchego.jtestforge.model.SemanticGap;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Decides which framework-semantic gaps the existing test suite already closes —
 * jtestforge-specification.md §7.5.
 *
 * <p>Suppression is decided by the <b>shape</b> of the assertions a test makes, never by
 * its name. A name is a convention the target project may not follow, and a test called
 * {@code findById_requestMapping_isVerified} can perfectly well assert nothing about the
 * mapping at all.
 *
 * <p>The shapes themselves are recognised by {@link AssertionEvidenceScanner}, shared with
 * phase 11's semantic-gap guard - the two ends of the same loop, which must agree or
 * JTestForge would raise a gap, generate a test for it, accept it, and then raise the same
 * gap again on the next run.
 */
public final class GapSuppressionDetector {

    private final JavaParser javaParser = new JavaParser(new ParserConfiguration()
            .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21));
    private final AssertionEvidenceScanner evidenceScanner = new AssertionEvidenceScanner();

    /**
     * Removes every gap that some existing test already closes.
     *
     * <p>An unreadable or absent test file yields no evidence rather than failing: the
     * safe direction is to leave the gap open, since raising a gap that is in fact covered
     * costs one discarded generation, while wrongly suppressing one leaves a contract
     * unverified with nothing to indicate it.
     */
    public List<SemanticGap> removeSuppressed(List<SemanticGap> gaps, List<Path> testFiles) {
        List<AssertionEvidence> evidence = new ArrayList<>();
        for (Path testFile : testFiles) {
            evidence.addAll(evidenceIn(testFile));
        }
        return gaps.stream()
                .filter(gap -> evidence.stream().noneMatch(found -> closes(found, gap)))
                .toList();
    }

    /** The assertion shapes an existing test file demonstrates. */
    public List<AssertionEvidence> evidenceIn(Path testFile) {
        return parseQuietly(testFile)
                .map(evidenceScanner::scan)
                .orElseGet(List::of);
    }

    /** Whether {@code evidence} is enough to consider {@code gap} closed. */
    public boolean closes(AssertionEvidence evidence, SemanticGap gap) {
        if (evidence.shape() != gap.requiredShape()) {
            return false;
        }
        if (gap.path() == null || gap.path().isBlank()) {
            return true;
        }
        return httpMethodMatches(evidence.httpMethod(), gap.httpMethod())
                && pathMatches(evidence.path(), gap.path());
    }

    private boolean httpMethodMatches(String evidenceMethod, String gapMethod) {
        if (gapMethod == null || "ANY".equals(gapMethod)) {
            return true;
        }
        return gapMethod.equalsIgnoreCase(evidenceMethod);
    }

    /**
     * Matches a concrete requested path against the mapping template it satisfies -
     * {@code /api/orders/42} against {@code /api/orders/&#123;id&#125;}. Without this,
     * every path-variable mapping would look permanently unverified no matter how
     * thoroughly it was tested.
     */
    public boolean pathMatches(String requestedPath, String mappingTemplate) {
        if (requestedPath == null) {
            return false;
        }
        String withoutQuery = requestedPath.split("\\?")[0];
        if (withoutQuery.equals(mappingTemplate)) {
            return true;
        }
        StringBuilder regex = new StringBuilder();
        int index = 0;
        while (index < mappingTemplate.length()) {
            char character = mappingTemplate.charAt(index);
            if (character == '{') {
                int close = mappingTemplate.indexOf('}', index);
                if (close < 0) {
                    break;
                }
                regex.append("[^/]+");
                index = close + 1;
            } else {
                regex.append(Pattern.quote(String.valueOf(character)));
                index++;
            }
        }
        return withoutQuery.matches(regex.toString());
    }

    private Optional<CompilationUnit> parseQuietly(Path testFile) {
        if (!Files.isRegularFile(testFile)) {
            return Optional.empty();
        }
        try {
            ParseResult<CompilationUnit> result = javaParser.parse(testFile);
            return result.getResult();
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
