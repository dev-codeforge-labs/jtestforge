package com.devmanchego.jtestforge.guard;

import com.devmanchego.jtestforge.config.GenerateConfig;
import com.devmanchego.jtestforge.config.SpringConfig;
import com.devmanchego.jtestforge.model.TestCandidate;
import com.devmanchego.jtestforge.model.Tier;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StaticQualityGuardsTest {

    private final StaticQualityGuards guards = new StaticQualityGuards(
            new GenerateConfig(null, null, null, null, null), defaultSpringConfig());

    @Test
    void noGoodCandidateIsEverRejected() {
        // The hard requirement of this phase. A guard that discards a genuinely useful
        // test burns an AI invocation, loses real coverage, and teaches whoever is
        // watching that the tool's judgement cannot be trusted.
        List<String> falseRejections = new ArrayList<>();

        for (TestCandidate candidate : GuardCorpus.good()) {
            List<GuardRejection> rejections = guards.evaluate(candidate, contextFor(candidate));
            rejections.forEach(rejection -> falseRejections.add(rejection.toString()));
        }

        assertThat(falseRejections)
                .as("guards must not reject any candidate in the hand-labelled good set")
                .isEmpty();
    }

    @Test
    void everyBadCandidateIsCaughtByTheGuardItWasLabelledFor() {
        List<String> misses = new ArrayList<>();

        for (GuardCorpus.LabelledBadCandidate labelled : GuardCorpus.bad()) {
            List<GuardId> firedGuards = guards.evaluate(labelled.candidate(), contextFor(labelled.candidate()))
                    .stream().map(GuardRejection::guardId).toList();
            if (!firedGuards.contains(labelled.expectedGuard())) {
                misses.add(labelled.candidate().methodName()
                        + " expected " + labelled.expectedGuard() + " but got " + firedGuards);
            }
        }

        assertThat(misses).isEmpty();
    }

    @Test
    void everyRejectionCarriesAReadableReasonAndTheMethodItRejected() {
        GuardCorpus.LabelledBadCandidate labelled = GuardCorpus.bad().get(0);

        List<GuardRejection> rejections = guards.evaluate(
                labelled.candidate(), contextFor(labelled.candidate()));

        assertThat(rejections).isNotEmpty();
        assertThat(rejections).allSatisfy(rejection -> {
            assertThat(rejection.methodName()).isEqualTo(labelled.candidate().methodName());
            assertThat(rejection.reason()).isNotBlank();
        });
    }

    @Test
    void aVerifyOnlyTestIsAcceptedOnAVoidMethodAndRejectedOnOneThatReturnsAValue() {
        // §11.1 guard 2's exemption: on a void method there is no return value to assert,
        // so the interaction genuinely is the whole contract.
        TestCandidate verifyOnly = GuardCorpus.candidate("verifiesTheInteraction", """
                @Test
                void verifiesTheInteraction() {
                    subject.post("ENTRY-1");
                    verify(gateway).record("ENTRY-1");
                }
                """);

        List<GuardRejection> onVoid = guards.evaluate(verifyOnly, new GuardContext(
                GuardCorpus.testClassWithMocks(), GuardCorpus.voidMethod(), Tier.PLAIN_UNIT, List.of()));
        List<GuardRejection> onValueReturning = guards.evaluate(verifyOnly, new GuardContext(
                GuardCorpus.testClassWithMocks(), GuardCorpus.methodReturning("java.math.BigDecimal"),
                Tier.PLAIN_UNIT, List.of()));

        assertThat(onVoid).isEmpty();
        assertThat(onValueReturning).extracting(GuardRejection::guardId).contains(GuardId.MOCK_ONLY);
    }

    @Test
    void theMockOnlyGuardCanBeSwitchedOffInConfiguration() {
        StaticQualityGuards permissive = new StaticQualityGuards(
                new GenerateConfig(null, null, null, null, false), defaultSpringConfig());
        TestCandidate verifyOnly = GuardCorpus.candidate("verifiesOnly", """
                @Test
                void verifiesOnly() {
                    subject.applyFee(new BigDecimal("100"), EUR);
                    verify(gateway).charge(any());
                }
                """);

        assertThat(permissive.evaluate(verifyOnly, GuardCorpus.plainUnitContext()))
                .extracting(GuardRejection::guardId).doesNotContain(GuardId.MOCK_ONLY);
    }

    @Test
    void theNoAssertionGuardCanBeSwitchedOffInConfiguration() {
        StaticQualityGuards permissive = new StaticQualityGuards(
                new GenerateConfig(null, null, null, false, null), defaultSpringConfig());
        TestCandidate assertsNothing = GuardCorpus.candidate("assertsNothing", """
                @Test
                void assertsNothing() {
                    subject.applyFee(new BigDecimal("100"), EUR);
                }
                """);

        assertThat(permissive.evaluate(assertsNothing, GuardCorpus.plainUnitContext()))
                .extracting(GuardRejection::guardId).doesNotContain(GuardId.NO_ASSERTION);
    }

    @Test
    void aDuplicateNameIsRejectedAgainstTheExistingTestClass() {
        var testClass = new com.devmanchego.jtestforge.model.TestClassInfo(
                java.nio.file.Path.of("PaymentServiceTest.java"), "PaymentServiceTest", "com.acme",
                java.util.Set.of("alreadyThere"), List.of(),
                com.devmanchego.jtestforge.model.InjectionStyle.INJECT_MOCKS,
                com.devmanchego.jtestforge.model.AssertionLibrary.ASSERTJ,
                List.of(), java.util.Map.of(), java.util.Set.of(), java.util.Set.of());
        TestCandidate colliding = GuardCorpus.candidate("alreadyThere", """
                @Test
                void alreadyThere() {
                    assertThat(subject.classify(500)).isEqualTo(2);
                }
                """);

        List<GuardRejection> rejections = guards.evaluate(colliding, new GuardContext(
                testClass, GuardCorpus.methodReturning("int"), Tier.PLAIN_UNIT, List.of()));

        assertThat(rejections).extracting(GuardRejection::guardId).contains(GuardId.DUPLICATE_NAME);
    }

    @Test
    void theSpringGuardsNeverFireOnAPlainUnitCandidate() {
        // A plain Mockito test has no context to fork, no slice to turn into an
        // integration test, and no entity manager to flush.
        TestCandidate plain = GuardCorpus.candidate("aPlainUnitTest", """
                @Test
                void aPlainUnitTest() {
                    assertThat(subject.classify(500)).isEqualTo(2);
                }
                """);

        List<GuardId> fired = guards.evaluate(plain, GuardCorpus.plainUnitContext())
                .stream().map(GuardRejection::guardId).toList();

        assertThat(fired).doesNotContain(GuardId.BARE_STATUS, GuardId.CONTEXT_KEY,
                GuardId.ENVIRONMENT, GuardId.PERSISTENCE_HYGIENE, GuardId.SEMANTIC_GAP_SHAPE);
    }

    @Test
    void aCandidateGeneratedForAMappingGapMustActuallyRequestThatMapping() {
        // §11.1 guard 7: the candidate must have the assertion shape its gap requires,
        // or it cannot close the gap it was generated for.
        TestCandidate wrongEndpoint = GuardCorpus.candidate("requestsSomeOtherEndpoint", """
                @Test
                void requestsSomeOtherEndpoint() throws Exception {
                    mockMvc.perform(get("/api/customers/42"))
                            .andExpect(status().isOk())
                            .andExpect(content().string("customer-42"));
                }
                """);
        GuardContext withGap = new GuardContext(GuardCorpus.testClassWithMocks(),
                GuardCorpus.methodReturning("org.springframework.http.ResponseEntity"),
                Tier.WEB_SLICE, List.of(GuardCorpus.mappingGap("GET", "/api/orders/{id}")));

        assertThat(guards.evaluate(wrongEndpoint, withGap))
                .extracting(GuardRejection::guardId).contains(GuardId.SEMANTIC_GAP_SHAPE);
    }

    @Test
    void aCandidateThatDoesRequestTheGapsMappingIsAccepted() {
        TestCandidate rightEndpoint = GuardCorpus.candidate("requestsTheMappedEndpoint", """
                @Test
                void requestsTheMappedEndpoint() throws Exception {
                    mockMvc.perform(get("/api/orders/42"))
                            .andExpect(status().isOk())
                            .andExpect(content().string("order-42"));
                }
                """);
        GuardContext withGap = new GuardContext(GuardCorpus.testClassWithMocks(),
                GuardCorpus.methodReturning("org.springframework.http.ResponseEntity"),
                Tier.WEB_SLICE, List.of(GuardCorpus.mappingGap("GET", "/api/orders/{id}")));

        assertThat(guards.evaluate(rightEndpoint, withGap)).isEmpty();
    }

    /** Routes each corpus candidate to the tier its content implies. */
    private GuardContext contextFor(TestCandidate candidate) {
        String source = candidate.sourceCode();
        if (source.contains("entityManager")) {
            return GuardCorpus.dataSliceContext();
        }
        if (source.contains("mockMvc") || source.contains("testRestTemplate")
                || source.contains("GenericContainer")) {
            return GuardCorpus.webSliceContext();
        }
        if (source.contains("verify(gateway).record")) {
            return new GuardContext(GuardCorpus.testClassWithMocks(), GuardCorpus.voidMethod(),
                    Tier.PLAIN_UNIT, List.of());
        }
        return GuardCorpus.plainUnitContext();
    }

    private static SpringConfig defaultSpringConfig() {
        return new SpringConfig(null, null, null, null, null, null, null, null, null, null, null);
    }
}
