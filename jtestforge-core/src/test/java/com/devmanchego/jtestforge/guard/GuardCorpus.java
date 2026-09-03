package com.devmanchego.jtestforge.guard;

import com.devmanchego.jtestforge.model.MockField;
import com.devmanchego.jtestforge.model.ProductionMethod;
import com.devmanchego.jtestforge.model.SemanticGap;
import com.devmanchego.jtestforge.model.SemanticGapKind;
import com.devmanchego.jtestforge.model.TestCandidate;
import com.devmanchego.jtestforge.model.TestClassInfo;
import com.devmanchego.jtestforge.model.Tier;
import com.devmanchego.jtestforge.model.Visibility;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The hand-labelled corpus the quality guards are judged against —
 * jtestforge-implementation-plan.md phase 11.
 *
 * <p>Two sets, and they are not symmetric in importance. <b>Zero false rejections on the
 * good set is the hard requirement</b>: a guard that discards a genuinely useful test
 * burns an AI invocation, loses real coverage, and - worst - teaches whoever is watching
 * that the tool's judgement cannot be trusted. A missed bad test merely survives to be
 * caught by a later gate (it still has to compile, pass, and move a metric).
 *
 * <p>Kept as a shared fixture rather than inline strings so both the guard tests and any
 * later regression work read from the same labelled data.
 */
final class GuardCorpus {

    private GuardCorpus() {
    }

    // --- the good set: every one of these must be accepted --------------------------

    static List<TestCandidate> good() {
        return List.of(
                candidate("assertsAComputedValue", """
                        @Test
                        void assertsAComputedValue() {
                            assertThat(subject.applyFee(new BigDecimal("100"), EUR)).isEqualByComparingTo("101.00");
                        }
                        """),
                candidate("assertsWithJUnitAssertions", """
                        @Test
                        void assertsWithJUnitAssertions() {
                            assertEquals(2, subject.classify(500));
                        }
                        """),
                candidate("assertsAThrownExceptionAndItsMessage", """
                        @Test
                        void assertsAThrownExceptionAndItsMessage() {
                            IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                                    () -> subject.applyFee(new BigDecimal("-1"), EUR));
                            assertThat(thrown).hasMessageContaining("must not be negative");
                        }
                        """),
                candidate("stubsAMockThenAssertsTheSubjectsResult", """
                        @Test
                        void stubsAMockThenAssertsTheSubjectsResult() {
                            when(gateway.charge(any())).thenReturn(true);
                            assertThat(subject.settle("INV-1")).isTrue();
                        }
                        """),
                candidate("usesAnArgumentCaptorAndAssertsWhatWasCaptured", """
                        @Test
                        void usesAnArgumentCaptorAndAssertsWhatWasCaptured() {
                            subject.settle("INV-1");
                            ArgumentCaptor<BigDecimal> captor = ArgumentCaptor.forClass(BigDecimal.class);
                            verify(gateway).charge(captor.capture());
                            assertThat(captor.getValue()).isEqualByComparingTo("1.00");
                        }
                        """),
                candidate("assertsWithACustomHelperMethod", """
                        @Test
                        void assertsWithACustomHelperMethod() {
                            assertOrderIsValid(subject.create("REF-1"));
                        }
                        """),
                candidate("usesAssertAllToGroupRelatedChecks", """
                        @Test
                        void usesAssertAllToGroupRelatedChecks() {
                            Receipt receipt = subject.settle("INV-1");
                            assertAll(
                                    () -> assertEquals("INV-1", receipt.reference()),
                                    () -> assertEquals(Status.PAID, receipt.status()));
                        }
                        """),
                candidate("usesAFixedClockRatherThanTheSystemClock", """
                        @Test
                        void usesAFixedClockRatherThanTheSystemClock() {
                            Clock fixed = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
                            assertThat(new Reporter(fixed).today()).isEqualTo(LocalDate.of(2026, 1, 1));
                        }
                        """),
                candidate("usesASeededRandomForDeterminism", """
                        @Test
                        void usesASeededRandomForDeterminism() {
                            assertThat(subject.pick(new Random(42))).isEqualTo("b");
                        }
                        """),
                candidate("referencesARelativeApiPathNotAFilesystemPath", """
                        @Test
                        void referencesARelativeApiPathNotAFilesystemPath() throws Exception {
                            mockMvc.perform(get("/api/orders/42"))
                                    .andExpect(status().isOk())
                                    .andExpect(content().string("order-42"));
                        }
                        """),
                candidate("assertsARedirectToLocalhostWhichIsNotARealNetworkCall", """
                        @Test
                        void assertsARedirectToLocalhostWhichIsNotARealNetworkCall() throws Exception {
                            mockMvc.perform(post("/api/orders"))
                                    .andExpect(status().is3xxRedirection())
                                    .andExpect(redirectedUrl("http://localhost/api/orders/1"));
                        }
                        """),
                candidate("assertsBothStatusAndBodyOnARejectedPayload", """
                        @Test
                        void assertsBothStatusAndBodyOnARejectedPayload() throws Exception {
                            mockMvc.perform(post("/api/orders").content("{}"))
                                    .andExpect(status().isBadRequest())
                                    .andExpect(jsonPath("$.errors[0].field").value("reference"));
                        }
                        """),
                candidate("flushesAndClearsBeforeAssertingAPersistedMapping", """
                        @Test
                        void flushesAndClearsBeforeAssertingAPersistedMapping() {
                            entityManager.persist(new Order("REF-1"));
                            entityManager.flush();
                            entityManager.clear();
                            assertThat(orderRepository.findByReference("REF-1")).hasSize(1);
                        }
                        """),
                candidate("usesPersistAndFlushThenClears", """
                        @Test
                        void usesPersistAndFlushThenClears() {
                            entityManager.persistAndFlush(new Order("REF-1"));
                            entityManager.clear();
                            assertThat(orderRepository.findByReference("REF-1")).hasSize(1);
                        }
                        """),
                candidate("aParameterizedTestIsJustAsValid", """
                        @ParameterizedTest
                        @ValueSource(ints = {101, 500, 1000})
                        void aParameterizedTestIsJustAsValid(int amount) {
                            assertThat(subject.classify(amount)).isEqualTo(2);
                        }
                        """),
                candidate("verifiesOnlyBecauseTheMethodUnderTestReturnsNothing", """
                        @Test
                        void verifiesOnlyBecauseTheMethodUnderTestReturnsNothing() {
                            subject.post("ENTRY-1");
                            verify(gateway).record("ENTRY-1");
                        }
                        """),
                candidate("assertsOnASpyWhichIsAPartialRealObject", """
                        @Test
                        void assertsOnASpyWhichIsAPartialRealObject() {
                            assertThat(subject.formatAll(List.of(1, 2))).containsExactly("1", "2");
                        }
                        """),
                candidate("assertsStateChangedOnAVoidMethodThroughAGetter", """
                        @Test
                        void assertsStateChangedOnAVoidMethodThroughAGetter() {
                            subject.markSettled();
                            assertThat(subject.isSettled()).isTrue();
                        }
                        """),
                candidate("assertsAnEmptyCollectionResult", """
                        @Test
                        void assertsAnEmptyCollectionResult() {
                            when(gateway.history()).thenReturn(List.of());
                            assertThat(subject.recentPayments()).isEmpty();
                        }
                        """),
                candidate("assertsBoundaryBehaviourAtTheExactThreshold", """
                        @Test
                        void assertsBoundaryBehaviourAtTheExactThreshold() {
                            assertThat(subject.classify(100)).isEqualTo(1);
                            assertThat(subject.classify(101)).isEqualTo(2);
                        }
                        """));
    }

    // --- the bad set: each is labelled with the guard that must catch it -------------

    static List<LabelledBadCandidate> bad() {
        return List.of(
                bad(GuardId.NO_ASSERTION, "callsTheMethodAndAssertsNothing", """
                        @Test
                        void callsTheMethodAndAssertsNothing() {
                            subject.applyFee(new BigDecimal("100"), EUR);
                        }
                        """),
                bad(GuardId.NO_ASSERTION, "onlySetsUpMocksAndNeverChecksAnything", """
                        @Test
                        void onlySetsUpMocksAndNeverChecksAnything() {
                            when(gateway.charge(any())).thenReturn(true);
                            subject.settle("INV-1");
                        }
                        """),
                bad(GuardId.MOCK_ONLY, "onlyVerifiesAMockOnAMethodThatReturnsAValue", """
                        @Test
                        void onlyVerifiesAMockOnAMethodThatReturnsAValue() {
                            subject.applyFee(new BigDecimal("100"), EUR);
                            verify(gateway).charge(any());
                        }
                        """),
                bad(GuardId.MOCK_ONLY, "verifiesNoMoreInteractionsAndNothingElse", """
                        @Test
                        void verifiesNoMoreInteractionsAndNothingElse() {
                            subject.applyFee(new BigDecimal("100"), EUR);
                            verifyNoMoreInteractions(gateway);
                        }
                        """),
                bad(GuardId.TAUTOLOGY, "assertsTheStubbedValueCameBackFromTheMockItself", """
                        @Test
                        void assertsTheStubbedValueCameBackFromTheMockItself() {
                            when(gateway.charge(any())).thenReturn(true);
                            assertThat(gateway.charge(new BigDecimal("1"))).isTrue();
                        }
                        """),
                bad(GuardId.TAUTOLOGY, "assertsAMocksReturnValueWithJUnitAssertions", """
                        @Test
                        void assertsAMocksReturnValueWithJUnitAssertions() {
                            when(gateway.history()).thenReturn(List.of("a"));
                            assertEquals(List.of("a"), gateway.history());
                        }
                        """),
                bad(GuardId.FORBIDDEN_CONSTRUCT, "sleepsToWaitForSomething", """
                        @Test
                        void sleepsToWaitForSomething() throws Exception {
                            subject.startAsync();
                            Thread.sleep(500);
                            assertThat(subject.isDone()).isTrue();
                        }
                        """),
                bad(GuardId.FORBIDDEN_CONSTRUCT, "printsToStandardOutInsteadOfAsserting", """
                        @Test
                        void printsToStandardOutInsteadOfAsserting() {
                            System.out.println(subject.applyFee(new BigDecimal("100"), EUR));
                            assertThat(true).isTrue();
                        }
                        """),
                bad(GuardId.FORBIDDEN_CONSTRUCT, "isDisabledSoItNeverRuns", """
                        @Test
                        @Disabled("flaky")
                        void isDisabledSoItNeverRuns() {
                            assertThat(subject.classify(500)).isEqualTo(2);
                        }
                        """),
                bad(GuardId.FORBIDDEN_CONSTRUCT, "usesTheSystemClockSoItWillEventuallyBreak", """
                        @Test
                        void usesTheSystemClockSoItWillEventuallyBreak() {
                            assertThat(subject.report().date()).isEqualTo(LocalDate.now());
                        }
                        """),
                bad(GuardId.FORBIDDEN_CONSTRUCT, "usesUnseededRandomness", """
                        @Test
                        void usesUnseededRandomness() {
                            assertThat(subject.pick(new Random())).isNotNull();
                        }
                        """),
                bad(GuardId.FORBIDDEN_CONSTRUCT, "readsARealFilesystemPath", """
                        @Test
                        void readsARealFilesystemPath() {
                            assertThat(subject.load("/var/lib/acme/orders.csv")).isNotEmpty();
                        }
                        """),
                bad(GuardId.FORBIDDEN_CONSTRUCT, "callsARealNetworkAddress", """
                        @Test
                        void callsARealNetworkAddress() {
                            assertThat(subject.fetch("https://api.example.com/orders")).isNotNull();
                        }
                        """),
                bad(GuardId.BARE_STATUS, "assertsOnlyThatTheEndpointReturnedOk", """
                        @Test
                        void assertsOnlyThatTheEndpointReturnedOk() throws Exception {
                            mockMvc.perform(get("/api/orders/42")).andExpect(status().isOk());
                        }
                        """),
                bad(GuardId.BARE_STATUS, "assertsOnlyA400WithoutSayingWhichConstraintFailed", """
                        @Test
                        void assertsOnlyA400WithoutSayingWhichConstraintFailed() throws Exception {
                            mockMvc.perform(post("/api/orders").content("{}"))
                                    .andExpect(status().isBadRequest());
                        }
                        """),
                bad(GuardId.CONTEXT_KEY, "carriesDirtiesContextAndEvictsTheSharedCache", """
                        @Test
                        @DirtiesContext
                        void carriesDirtiesContextAndEvictsTheSharedCache() throws Exception {
                            mockMvc.perform(get("/api/orders/42"))
                                    .andExpect(status().isOk())
                                    .andExpect(content().string("order-42"));
                        }
                        """),
                bad(GuardId.CONTEXT_KEY, "declaresItsOwnMockBeanAndForksTheContext", """
                        @Test
                        @MockitoBean
                        void declaresItsOwnMockBeanAndForksTheContext() throws Exception {
                            mockMvc.perform(get("/api/orders/42"))
                                    .andExpect(status().isOk())
                                    .andExpect(content().string("order-42"));
                        }
                        """),
                bad(GuardId.ENVIRONMENT, "startsARealServerTurningTheSliceIntoAnIntegrationTest", """
                        @Test
                        void startsARealServerTurningTheSliceIntoAnIntegrationTest() {
                            ResponseEntity<String> response = testRestTemplate.getForEntity("/api/orders/42", String.class);
                            assertThat(response.getBody()).isEqualTo("order-42");
                        }
                        """),
                bad(GuardId.ENVIRONMENT, "spinsUpATestcontainer", """
                        @Test
                        void spinsUpATestcontainer() {
                            GenericContainer<?> database = new GenericContainer<>("postgres:16");
                            database.start();
                            assertThat(database.isRunning()).isTrue();
                        }
                        """),
                bad(GuardId.PERSISTENCE_HYGIENE, "readsBackThroughTheFirstLevelCacheWithoutFlushing", """
                        @Test
                        void readsBackThroughTheFirstLevelCacheWithoutFlushing() {
                            entityManager.persist(new Order("REF-1"));
                            assertThat(orderRepository.findByReference("REF-1")).hasSize(1);
                        }
                        """),
                bad(GuardId.PERSISTENCE_HYGIENE, "flushesButNeverClearsSoTheCacheStillAnswers", """
                        @Test
                        void flushesButNeverClearsSoTheCacheStillAnswers() {
                            entityManager.persist(new Order("REF-1"));
                            entityManager.flush();
                            assertThat(orderRepository.findByReference("REF-1")).hasSize(1);
                        }
                        """));
    }

    // --- shared fixtures --------------------------------------------------------------

    static TestCandidate candidate(String name, String source) {
        return new TestCandidate(name, source, List.of());
    }

    static LabelledBadCandidate bad(GuardId expectedGuard, String name, String source) {
        return new LabelledBadCandidate(expectedGuard, candidate(name, source));
    }

    /** A test class with the mocks the corpus refers to, and no tests of its own yet. */
    static TestClassInfo testClassWithMocks() {
        return new TestClassInfo(Path.of("PaymentServiceTest.java"), "PaymentServiceTest", "com.acme",
                Set.of(), List.of(new MockField("gateway", "PaymentGateway", "Mock")),
                com.devmanchego.jtestforge.model.InjectionStyle.INJECT_MOCKS,
                com.devmanchego.jtestforge.model.AssertionLibrary.ASSERTJ,
                List.of(), Map.of(), Set.of(), Set.of());
    }

    static ProductionMethod methodReturning(String returnType) {
        return new ProductionMethod("applyFee", returnType, List.of(), Visibility.PUBLIC, false,
                List.of(), Map.of(), List.of(), 10, 20, 3);
    }

    static ProductionMethod voidMethod() {
        return new ProductionMethod("post", "void", List.of(), Visibility.PUBLIC, false,
                List.of(), Map.of(), List.of(), 10, 20, 1);
    }

    static GuardContext plainUnitContext() {
        return new GuardContext(testClassWithMocks(), methodReturning("java.math.BigDecimal"),
                Tier.PLAIN_UNIT, List.of());
    }

    static GuardContext webSliceContext() {
        return new GuardContext(testClassWithMocks(), methodReturning("org.springframework.http.ResponseEntity"),
                Tier.WEB_SLICE, List.of());
    }

    static GuardContext dataSliceContext() {
        return new GuardContext(testClassWithMocks(), methodReturning("java.util.List"),
                Tier.DATA_SLICE, List.of());
    }

    static SemanticGap mappingGap(String httpMethod, String path) {
        return new SemanticGap(SemanticGapKind.REQUEST_MAPPING, "com.acme.web.OrderController",
                "findById", "The mapping is not verified.", httpMethod, path, 10);
    }

    record LabelledBadCandidate(GuardId expectedGuard, TestCandidate candidate) {
    }
}
