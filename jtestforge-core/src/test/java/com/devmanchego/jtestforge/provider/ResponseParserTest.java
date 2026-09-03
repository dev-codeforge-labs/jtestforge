package com.devmanchego.jtestforge.provider;

import com.devmanchego.jtestforge.model.TestCandidate;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * jtestforge-specification.md §6.2's response contract, exercised over a committed corpus
 * of real-shaped model answers - written before {@link ResponseParser} itself, per
 * jtestforge-implementation-plan.md phase 9.
 *
 * <p>Two very different outcomes are tested throughout: a <b>fatal</b> shape (no usable
 * {@code java} block at all) fails the whole response, driving the single corrective
 * re-prompt; a <b>per-declaration</b> problem (a bad method among good ones) drops just
 * that declaration and keeps the rest - conflating the two would throw away good
 * candidates over one bad one.
 */
class ResponseParserTest {

    private final ResponseParser parser = new ResponseParser();

    @Test
    void aWellFormedResponseYieldsEveryMethodWithTheSharedImportsAttachedToEach() {
        String response = """
                ```imports
                org.mockito.ArgumentCaptor
                java.math.RoundingMode
                ```

                ```java
                @Test
                void appliesTheFlatFeeBelowTheThreshold() {
                    assertEquals(1, 1);
                }

                @Test
                void appliesThePercentageFeeAboveTheThreshold() {
                    assertEquals(2, 2);
                }
                ```
                """;

        ResponseParseResult result = parser.parse(response, Set.of());

        assertThat(result.isFatal()).isFalse();
        assertThat(result.candidates()).extracting(TestCandidate::methodName)
                .containsExactly("appliesTheFlatFeeBelowTheThreshold", "appliesThePercentageFeeAboveTheThreshold");
        assertThat(result.candidates()).allSatisfy(candidate ->
                assertThat(candidate.requiredImports())
                        .containsExactly("org.mockito.ArgumentCaptor", "java.math.RoundingMode"));
        assertThat(result.dropped()).isEmpty();
    }

    @Test
    void proseBeforeBetweenAndAfterTheFencedBlocksIsIgnored() {
        String response = """
                Here are the additional tests you asked for.

                ```imports
                java.time.Clock
                ```

                A brief explanation of the approach follows.

                ```java
                @Test
                void usesAFixedClock() {
                    assertEquals(1, 1);
                }
                ```

                Let me know if you would like any adjustments!
                """;

        ResponseParseResult result = parser.parse(response, Set.of());

        assertThat(result.isFatal()).isFalse();
        assertThat(result.candidates()).extracting(TestCandidate::methodName).containsExactly("usesAFixedClock");
    }

    @Test
    void aResponseWithNoImportsBlockAtAllIsStillUsable() {
        // A candidate needing no new imports is the common case, not an error.
        String response = """
                ```java
                @Test
                void needsNoNewImports() {
                    assertEquals(1, 1);
                }
                ```
                """;

        ResponseParseResult result = parser.parse(response, Set.of());

        assertThat(result.isFatal()).isFalse();
        assertThat(result.candidates()).singleElement()
                .satisfies(candidate -> assertThat(candidate.requiredImports()).isEmpty());
    }

    @Test
    void aFullCompilationUnitInsteadOfBodyDeclarationsIsAFatalViolation() {
        String response = """
                ```java
                package com.acme;

                import org.junit.jupiter.api.Test;

                class PaymentServiceTest {
                    @Test
                    void t() {
                        assertEquals(1, 1);
                    }
                }
                ```
                """;

        ResponseParseResult result = parser.parse(response, Set.of());

        assertThat(result.isFatal()).isTrue();
        assertThat(result.violation().kind()).isEqualTo(ContractViolationKind.JAVA_BLOCK_NOT_BODY_DECLARATIONS);
        assertThat(result.candidates()).isEmpty();
    }

    @Test
    void aWholeClassDeclarationWithNoPackageOrImportsIsAlsoAFatalViolation() {
        String response = """
                ```java
                class PaymentServiceTest {
                    @Test
                    void t() {
                        assertEquals(1, 1);
                    }
                }
                ```
                """;

        ResponseParseResult result = parser.parse(response, Set.of());

        assertThat(result.isFatal()).isTrue();
        assertThat(result.violation().kind()).isEqualTo(ContractViolationKind.JAVA_BLOCK_NOT_BODY_DECLARATIONS);
    }

    @Test
    void aMethodMissingTheTestAnnotationIsDroppedButGoodMethodsSurvive() {
        String response = """
                ```java
                void helperNotAnnotatedAsATest() {
                    doSomething();
                }

                @Test
                void aGenuineTest() {
                    assertEquals(1, 1);
                }
                ```
                """;

        ResponseParseResult result = parser.parse(response, Set.of());

        assertThat(result.isFatal()).isFalse();
        assertThat(result.candidates()).extracting(TestCandidate::methodName).containsExactly("aGenuineTest");
        assertThat(result.dropped()).anySatisfy(dropped ->
                assertThat(dropped.description()).contains("helperNotAnnotatedAsATest"));
    }

    @Test
    void aParameterizedTestAnnotationIsAcceptedJustLikeTest() {
        String response = """
                ```java
                @ParameterizedTest
                @ValueSource(ints = {1, 2, 3})
                void handlesSeveralValues(int value) {
                    assertTrue(value > 0);
                }
                ```
                """;

        ResponseParseResult result = parser.parse(response, Set.of());

        assertThat(result.isFatal()).isFalse();
        assertThat(result.candidates()).extracting(TestCandidate::methodName).containsExactly("handlesSeveralValues");
    }

    @Test
    void aNameDuplicatingAnExistingTestMethodIsDropped() {
        String response = """
                ```java
                @Test
                void applyFee_roundsHalfUp() {
                    assertEquals(1, 1);
                }

                @Test
                void aNewTestNotSeenBefore() {
                    assertEquals(2, 2);
                }
                ```
                """;

        ResponseParseResult result = parser.parse(response, Set.of("applyFee_roundsHalfUp"));

        assertThat(result.isFatal()).isFalse();
        assertThat(result.candidates()).extracting(TestCandidate::methodName).containsExactly("aNewTestNotSeenBefore");
        assertThat(result.dropped()).anySatisfy(dropped ->
                assertThat(dropped.description()).contains("applyFee_roundsHalfUp"));
    }

    @Test
    void twoMethodsWithTheSameNameWithinOneResponseKeepsOnlyTheFirst() {
        String response = """
                ```java
                @Test
                void duplicatedWithinThisResponse() {
                    assertEquals(1, 1);
                }

                @Test
                void duplicatedWithinThisResponse() {
                    assertEquals(2, 2);
                }
                ```
                """;

        ResponseParseResult result = parser.parse(response, Set.of());

        assertThat(result.candidates()).hasSize(1);
        assertThat(result.dropped()).hasSize(1);
    }

    @Test
    void aFieldSmuggledIntoTheBlockIsDroppedAndTheGoodMethodsSurvive() {
        String response = """
                ```java
                private String sneakyField = "should not be here";

                @Test
                void aGenuineTest() {
                    assertEquals(1, 1);
                }
                ```
                """;

        ResponseParseResult result = parser.parse(response, Set.of());

        assertThat(result.isFatal()).isFalse();
        assertThat(result.candidates()).extracting(TestCandidate::methodName).containsExactly("aGenuineTest");
        assertThat(result.dropped()).anySatisfy(dropped ->
                assertThat(dropped.description()).contains("sneakyField"));
    }

    @Test
    void anInnerClassSmuggledIntoTheBlockIsDropped() {
        String response = """
                ```java
                @Test
                void aGenuineTest() {
                    assertEquals(1, 1);
                }

                static class SneakyHelper {
                    void doStuff() {}
                }
                ```
                """;

        ResponseParseResult result = parser.parse(response, Set.of());

        assertThat(result.isFatal()).isFalse();
        assertThat(result.candidates()).extracting(TestCandidate::methodName).containsExactly("aGenuineTest");
        assertThat(result.dropped()).anySatisfy(dropped ->
                assertThat(dropped.description()).contains("SneakyHelper"));
    }

    @Test
    void aStrayClassLevelAnnotationOnAMethodDoesNotBreakParsingAndIsPassedThroughForLaterGatesToJudge() {
        // Syntactically this is just "one more annotation on a method" - JavaParser
        // accepts it without complaint. Rejecting a candidate for annotation CONTENT is
        // ContextKeyGuard's job (§7.6), a separate gate; this parser must not duplicate
        // or pre-empt that decision.
        String response = """
                ```java
                @WebMvcTest(OrderController.class)
                @Test
                void carriesAStrayClassAnnotation() {
                    assertEquals(1, 1);
                }
                ```
                """;

        ResponseParseResult result = parser.parse(response, Set.of());

        assertThat(result.isFatal()).isFalse();
        assertThat(result.candidates()).singleElement()
                .satisfies(candidate -> assertThat(candidate.sourceCode()).contains("@WebMvcTest"));
    }

    @Test
    void anUnfencedAnswerIsAFatalViolation() {
        String response = """
                @Test
                void thisWasNeverFenced() {
                    assertEquals(1, 1);
                }
                """;

        ResponseParseResult result = parser.parse(response, Set.of());

        assertThat(result.isFatal()).isTrue();
        assertThat(result.violation().kind()).isEqualTo(ContractViolationKind.NOT_FENCED);
    }

    @Test
    void anEmptyAnswerIsAFatalViolation() {
        assertThat(parser.parse("", Set.of()).isFatal()).isTrue();
        assertThat(parser.parse("   \n  ", Set.of()).isFatal()).isTrue();
        assertThat(parser.parse(null, Set.of()).isFatal()).isTrue();
    }

    @Test
    void aTruncatedAnswerWithAnUnclosedFenceIsAFatalViolation() {
        String response = """
                ```java
                @Test
                void thisResponseGotCutOffMidGeneration() {
                    assertEquals(1, 1
                """;

        ResponseParseResult result = parser.parse(response, Set.of());

        assertThat(result.isFatal()).isTrue();
        assertThat(result.violation().kind()).isEqualTo(ContractViolationKind.NOT_FENCED);
        assertThat(result.violation().message()).containsIgnoringCase("truncat");
    }

    @Test
    void aDisallowedWildcardImportIsDroppedButAnAllowedOneIsKept() {
        String response = """
                ```imports
                org.mockito.Mockito.*
                java.util.*
                ```

                ```java
                @Test
                void t() {
                    assertEquals(1, 1);
                }
                ```
                """;

        ResponseParseResult result = parser.parse(response, Set.of());

        assertThat(result.isFatal()).isFalse();
        assertThat(result.candidates()).singleElement().satisfies(candidate ->
                assertThat(candidate.requiredImports()).containsExactly("static org.mockito.Mockito.*"));
        assertThat(result.dropped()).anySatisfy(dropped -> assertThat(dropped.description()).contains("java.util.*"));
    }

    @Test
    void allThreeKnownStaticWildcardImportsAreAccepted() {
        String response = """
                ```imports
                org.mockito.Mockito.*
                org.assertj.core.api.Assertions.*
                org.junit.jupiter.api.Assertions.*
                ```

                ```java
                @Test
                void t() {
                    assertEquals(1, 1);
                }
                ```
                """;

        ResponseParseResult result = parser.parse(response, Set.of());

        assertThat(result.candidates()).singleElement().satisfies(candidate ->
                assertThat(candidate.requiredImports()).containsExactly(
                        "static org.mockito.Mockito.*", "static org.assertj.core.api.Assertions.*",
                        "static org.junit.jupiter.api.Assertions.*"));
    }

    /**
     * Found against a real AI CLI (tuning-loop pass 2): the model wrote the allowed
     * wildcard bare, without the {@code static} keyword the prompt asked for. That bare
     * form matches {@code ALLOWED_WILDCARD_IMPORTS} literally, so the old check kept it
     * as-is - merging a plain {@code import org.junit.jupiter.api.Assertions.*;}, which
     * does not bring the class's static {@code assert*} methods into scope and left every
     * unqualified call in the generated test unresolved.
     */
    @Test
    void anAllowedWildcardImportWrittenWithoutTheStaticKeywordIsStillKeptAsStatic() {
        String response = """
                ```imports
                org.junit.jupiter.api.Assertions.*
                ```

                ```java
                @Test
                void t() {
                    assertTrue(true);
                }
                ```
                """;

        ResponseParseResult result = parser.parse(response, Set.of());

        assertThat(result.candidates()).singleElement().satisfies(candidate ->
                assertThat(candidate.requiredImports())
                        .containsExactly("static org.junit.jupiter.api.Assertions.*"));
    }

    @Test
    void aNonWildcardImportAlwaysPassesThroughUnchanged() {
        String response = """
                ```imports
                java.math.BigDecimal
                ```

                ```java
                @Test
                void t() {
                    assertEquals(1, 1);
                }
                ```
                """;

        ResponseParseResult result = parser.parse(response, Set.of());

        assertThat(result.candidates()).singleElement().satisfies(candidate ->
                assertThat(candidate.requiredImports()).containsExactly("java.math.BigDecimal"));
    }

    /**
     * Found against a real AI CLI (jtestforge-implementation-plan.md's post-phase-18
     * tuning loop): the prompt asks for a bare import path, but the model sometimes
     * writes the full statement anyway. Left unstripped this both slips a wildcard
     * import past {@code filterImports} (whose check looks for a trailing {@code .*},
     * not {@code .*;}) and, once merged, produces an unparseable doubled
     * {@code import import ...;;} line.
     */
    @Test
    void aFullyQualifiedImportStatementIsNormalizedToTheBareForm() {
        String response = """
                ```imports
                import java.math.BigDecimal;
                import static org.junit.jupiter.api.Assertions.assertEquals;
                ```

                ```java
                @Test
                void t() {
                    assertEquals(1, 1);
                }
                ```
                """;

        ResponseParseResult result = parser.parse(response, Set.of());

        assertThat(result.candidates()).singleElement().satisfies(candidate ->
                assertThat(candidate.requiredImports()).containsExactly(
                        "java.math.BigDecimal", "static org.junit.jupiter.api.Assertions.assertEquals"));
    }

    @Test
    void aFullyQualifiedAllowedWildcardImportStatementIsNormalizedAndKept() {
        String response = """
                ```imports
                import static org.junit.jupiter.api.Assertions.*;
                ```

                ```java
                @Test
                void t() {
                    assertEquals(1, 1);
                }
                ```
                """;

        ResponseParseResult result = parser.parse(response, Set.of());

        assertThat(result.candidates()).singleElement().satisfies(candidate ->
                assertThat(candidate.requiredImports())
                        .containsExactly("static org.junit.jupiter.api.Assertions.*"));
    }

    @Test
    void aFullyQualifiedDisallowedWildcardImportStatementIsStillCaughtByTheWildcardGuard() {
        String response = """
                ```imports
                import static java.util.*;
                ```

                ```java
                @Test
                void t() {
                    assertEquals(1, 1);
                }
                ```
                """;

        ResponseParseResult result = parser.parse(response, Set.of());

        assertThat(result.candidates()).singleElement().satisfies(candidate ->
                assertThat(candidate.requiredImports()).isEmpty());
        assertThat(result.dropped()).anySatisfy(dropped ->
                assertThat(dropped.description()).contains("java.util.*"));
    }

    @Test
    void aResponseWhereEveryDeclarationIsDroppedYieldsNoCandidatesButIsNotFatal() {
        // Structurally valid (a real list of body declarations), just useless. That is a
        // DISCARDED_NO_VALUE-flavoured outcome for the engine to record, not a contract
        // violation - the model followed the format, it just produced nothing usable.
        String response = """
                ```java
                private String notATest = "x";
                ```
                """;

        ResponseParseResult result = parser.parse(response, Set.of());

        assertThat(result.isFatal()).isFalse();
        assertThat(result.candidates()).isEmpty();
        assertThat(result.dropped()).hasSize(1);
    }
}
