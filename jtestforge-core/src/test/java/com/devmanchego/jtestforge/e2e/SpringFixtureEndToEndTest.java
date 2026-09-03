package com.devmanchego.jtestforge.e2e;

import com.devmanchego.jtestforge.model.ClassCoverage;
import com.devmanchego.jtestforge.model.RunState;
import com.devmanchego.jtestforge.model.Tier;
import com.devmanchego.jtestforge.model.UnitStatus;
import com.devmanchego.jtestforge.model.WorkUnit;
import com.devmanchego.jtestforge.orchestration.GenerateResult;
import com.devmanchego.jtestforge.orchestration.TierRestriction;
import com.devmanchego.jtestforge.spring.ContextKeyStabilityTracker;
import com.devmanchego.jtestforge.util.ExecutableResolver;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * jtestforge-implementation-plan.md phase 18 — the whole pipeline against a real Spring
 * Boot module, with real {@code mvn}, real Spring and real JaCoCo. Only the AI is
 * scripted.
 *
 * <p>This is where the assumptions the earlier phases could only assert against recorded
 * fixtures are finally checked against the tools that actually produce them: JaCoCo's own
 * method descriptors and line ranges, Spring's own context loading, Surefire's own report
 * shape, and the merge/revert round-trip against files a real compiler then reads.
 *
 * <p><b>Not covered here, and not silently:</b> {@code harden}. PIT
 * ({@code org.pitest:pitest-maven}) is not resolvable in this offline environment, so a
 * real mutation run cannot be performed at all - see {@code MutationReportParserTest},
 * which says the same thing about its own hand-authored fixture. The plan's "smoke run
 * against one real AI CLI" is likewise explicitly a manual, documented step rather than a
 * CI test.
 */
class SpringFixtureEndToEndTest {

    @BeforeEach
    void requireMaven() {
        Assumptions.assumeTrue(
                ExecutableResolver.isResolvable("mvn", ExecutableResolver.systemPathDirectories()),
                "mvn is not on PATH in this environment.");
        Assumptions.assumeTrue(Files.isDirectory(FixtureModuleHarness.CHECKED_IN_FIXTURE),
                "This test must run with jtestforge-core as the working directory.");
    }

    @Test
    void plainUnitPassRaisesRealLineCoverageAndLoadsZeroSpringContexts(@TempDir Path workDir) {
        FixtureModuleHarness harness = freshHarness(workDir);
        Map<String, ClassCoverage> before = harness.baselineCoverage();
        ContextKeyStabilityTracker tracker = new ContextKeyStabilityTracker(40);

        GenerateResult result = harness.runGenerate(
                plainUnitResponses(), TierRestriction.noSpring(), tracker);

        assertThat(result.exitReason()).isEqualTo(GenerateResult.ExitReason.COMPLETED);

        Map<String, ClassCoverage> after = harness.measureCoverageNow();
        assertThat(linesCovered(after, "com.acme.billing.PricingRules"))
                .as("the plain pass must reach branches the existing tests never did")
                .isGreaterThan(linesCovered(before, "com.acme.billing.PricingRules"));
        assertThat(linesMissed(after, "com.acme.billing.PricingRules"))
                .isLessThan(linesMissed(before, "com.acme.billing.PricingRules"));

        // §9.6: a --no-spring run must not load a single ApplicationContext.
        assertThat(tracker.contextLoads()).isZero();
    }

    @Test
    void aFullRunClosesFrameworkSemanticGapsOnAControllerAlreadyAtFullLineCoverage(@TempDir Path workDir) {
        // The end-to-end proof of gate 2, and the reason the Spring tiers exist at all.
        FixtureModuleHarness harness = freshHarness(workDir);

        // Established against REAL JaCoCo, not assumed: the controller starts with nothing
        // left for a coverage-driven tool to do.
        assertThat(linesMissed(harness.baselineCoverage(), "com.acme.billing.web.OrderController"))
                .as("the gate 2 premise: the controller is already fully line-covered")
                .isZero();

        ContextKeyStabilityTracker tracker = new ContextKeyStabilityTracker(40);
        GenerateResult result = harness.runGenerate(allResponses(), TierRestriction.allTiers(), tracker);

        assertThat(result.exitReason()).isEqualTo(GenerateResult.ExitReason.COMPLETED);

        RunState finalState = harness.stateStore().load().orElseThrow();
        Set<String> gapsClosed = finalState.units().stream()
                .filter(unit -> unit.tier() == Tier.WEB_SLICE)
                .flatMap(unit -> unit.semanticGapsClosed().stream())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());

        assertThat(gapsClosed)
                .as("a fully-covered controller still had verifiable contracts, and they were closed")
                .isNotEmpty();
        assertThat(finalState.units())
                .filteredOn(unit -> unit.tier() == Tier.WEB_SLICE)
                .anySatisfy(unit -> assertThat(unit.status()).isEqualTo(UnitStatus.DONE));

        // The generated slice test is real, compiled, passing Spring code on disk.
        Path webTest = workDir.resolve("src/test/java/com/acme/billing/web/OrderControllerWebTest.java");
        assertThat(webTest).exists();
        assertThat(readSafely(webTest)).contains("@WebMvcTest").contains("mockMvc");
    }

    @Test
    void theWholeRunStaysWithinAFixedContextLoadCount(@TempDir Path workDir) {
        // Asserted, not observed (the plan's own wording): this is the regression test for
        // context-key stability, and the number most likely to drift silently as templates
        // change. Two WEB_SLICE units share ONE test class and therefore ONE context key,
        // so a stable key means exactly one load - any more means something forked it.
        FixtureModuleHarness harness = freshHarness(workDir);
        ContextKeyStabilityTracker tracker = new ContextKeyStabilityTracker(40);

        harness.runGenerate(allResponses(), TierRestriction.allTiers(), tracker);

        assertThat(tracker.contextLoads())
                .as("two slice units on one controller must share exactly one context")
                .isEqualTo(1);
        assertThat(tracker.forkedClasses()).isEmpty();
    }

    @Test
    void runningGenerateTwiceAddsNothingTheSecondTime(@TempDir Path workDir) throws IOException {
        // Idempotence, and the practical proof that gap suppression (§7.5) and the
        // duplicate/no-value gates (§9.4 step 9, §11.1) work together. The claim being
        // tested is about WRITING, not about discovering: a second run may still raise
        // candidates, and must still end with the developer's files byte-identical.
        FixtureModuleHarness harness = freshHarness(workDir);
        harness.runGenerate(allResponses(), TierRestriction.allTiers(), new ContextKeyStabilityTracker(40));
        Map<String, String> afterFirstRun = readAllTestSources(workDir);

        FixtureModuleHarness second = new FixtureModuleHarness(workDir);
        second.preflightAndBaseline();
        Files.deleteIfExists(second.stateStore().stateFile());
        second.runGenerate(allResponses(), TierRestriction.allTiers(), new ContextKeyStabilityTracker(40));

        assertThat(readAllTestSources(workDir))
                .as("a second run must leave every test file exactly as the first one left it")
                .isEqualTo(afterFirstRun);
    }

    @Test
    void gapSuppressionRecognisesTheTestsTheFirstRunWrote(@TempDir Path workDir) {
        // The other half of idempotence: the gaps the first run closed are not raised
        // again, because the tests it wrote genuinely assert the shapes §7.5 asks for.
        FixtureModuleHarness harness = freshHarness(workDir);
        harness.runGenerate(allResponses(), TierRestriction.allTiers(), new ContextKeyStabilityTracker(40));

        FixtureModuleHarness second = new FixtureModuleHarness(workDir);
        second.preflightAndBaseline();

        assertThat(second.discoverUnits())
                .filteredOn(unit -> unit.workUnit().tier() == Tier.WEB_SLICE)
                .as("every framework-semantic gap the first run closed stays closed")
                .isEmpty();
    }

    @Test
    void aRunResumedAfterAnInterruptionReachesTheSameEndStateAsAnUninterruptedOne(@TempDir Path workDir) {
        // Simulates a kill after some units finished: the state file survives, and a
        // second invocation picks up exactly the units that never reached a terminal
        // status - reaching the same end state as if nothing had been interrupted.
        FixtureModuleHarness harness = freshHarness(workDir);
        harness.runGenerate(allResponses(), TierRestriction.noSpring(),
                new ContextKeyStabilityTracker(40), 2);

        RunState afterInterruption = harness.stateStore().load().orElseThrow();
        assertThat(afterInterruption.units())
                .as("maxUnitsPerRun stopped the run early, exactly as a kill would leave it")
                .anySatisfy(unit -> assertThat(unit.status()).isEqualTo(UnitStatus.PENDING));

        GenerateResult resumed = harness.runGenerate(
                allResponses(), TierRestriction.noSpring(), new ContextKeyStabilityTracker(40));

        assertThat(resumed.exitReason()).isEqualTo(GenerateResult.ExitReason.COMPLETED);
        RunState finalState = harness.stateStore().load().orElseThrow();
        assertThat(finalState.units())
                .filteredOn(unit -> unit.tier() == Tier.PLAIN_UNIT)
                .extracting(WorkUnit::status)
                .as("every plain unit reached a terminal status across the two invocations")
                .doesNotContain(UnitStatus.PENDING, UnitStatus.IN_PROGRESS);
    }

    // --- fixtures --------------------------------------------------------------------

    private FixtureModuleHarness freshHarness(Path workDir) {
        FixtureModuleHarness.copyFixtureTo(workDir);
        FixtureModuleHarness harness = new FixtureModuleHarness(workDir);
        harness.preflightAndBaseline();
        return harness;
    }

    private int linesCovered(Map<String, ClassCoverage> coverage, String fqn) {
        return coverage.get(fqn).linesCovered();
    }

    private int linesMissed(Map<String, ClassCoverage> coverage, String fqn) {
        return coverage.get(fqn).linesMissed();
    }

    private Map<String, String> readAllTestSources(Path workDir) throws IOException {
        Path testRoot = workDir.resolve("src/test/java");
        try (var walk = Files.walk(testRoot)) {
            return walk.filter(path -> path.toString().endsWith(".java"))
                    .collect(java.util.stream.Collectors.toMap(
                            path -> testRoot.relativize(path).toString(),
                            this::readSafely));
        }
    }

    private String readSafely(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private PromptRoutingAiProvider plainUnitResponses() {
        return plainUnitResponses(new PromptRoutingAiProvider());
    }

    /**
     * Web rules first: they are the more specific ones, and rule matching is
     * first-match-wins.
     */
    private PromptRoutingAiProvider allResponses() {
        return plainUnitResponses(webSliceResponses(new PromptRoutingAiProvider()));
    }

    /**
     * Scripted answers, ordered most-specific-first. Genuinely correct Java against the
     * real fixture: a real Maven build compiles and runs these, so a plausible-looking but
     * wrong answer fails the test rather than passing it.
     *
     * <p>Assertion calls are written fully qualified. A brand-new test class starts from
     * the generated skeleton, which carries no assertion imports at all, and the point of
     * these fixtures is to exercise the pipeline rather than to re-test the merger's own
     * import handling - which has its own tests. The one deliberate exception is the
     * web-slice answer below, which does declare an {@code imports} block, so the merge
     * path is exercised end to end at least once.
     */
    private PromptRoutingAiProvider plainUnitResponses(PromptRoutingAiProvider provider) {
        return provider
                .respondTo("fee(java.math.BigDecimal,boolean)", """
                        ```java
                        @Test
                        void fee_premiumRate_isChargedAtTheLowerRate() {
                            org.assertj.core.api.Assertions.assertThat(
                                            new PricingRules().fee(new java.math.BigDecimal("100.00"), true))
                                    .isEqualByComparingTo("1.40");
                        }

                        @Test
                        void fee_negativeAmount_isRejected() {
                            org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                                    () -> new PricingRules().fee(new java.math.BigDecimal("-1.00"), false));
                        }
                        ```
                        """)
                .respondTo("band(int)", """
                        ```java
                        @Test
                        void band_middleAmount_isBandTwo() {
                            org.assertj.core.api.Assertions.assertThat(new PricingRules().band(50_000)).isEqualTo(2);
                        }

                        @Test
                        void band_largeAmount_isBandThree() {
                            org.assertj.core.api.Assertions.assertThat(new PricingRules().band(500_000)).isEqualTo(3);
                        }

                        @Test
                        void band_negativeAmount_isRejected() {
                            org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                                    () -> new PricingRules().band(-1));
                        }
                        ```
                        """)
                .respondTo(java.util.List.of("findByReference(java.lang.String)", "com.acme.billing.OrderService"), """
                        ```java
                        @Test
                        void findByReference_blankReference_findsNothing() {
                            org.assertj.core.api.Assertions.assertThat(
                                    new OrderService().findByReference("  ")).isEmpty();
                        }

                        @Test
                        void findByReference_nullReference_findsNothing() {
                            org.assertj.core.api.Assertions.assertThat(
                                    new OrderService().findByReference(null)).isEmpty();
                        }
                        ```
                        """);
    }

    /**
     * The web-slice answers. Keyed on the unit's own gap sentence rather than on the target
     * method signature: the slice template is class-scoped - both of this controller's
     * units render the same class, the same endpoint list and the same mock beans - and
     * {@code {{FRAMEWORK_SEMANTIC_GAPS}}} is the only part of it that differs per unit.
     */
    private PromptRoutingAiProvider webSliceResponses(PromptRoutingAiProvider provider) {
        return provider
                .respondTo("No test issues a GET request to /api/orders/{reference}", """
                        ```imports
                        com.acme.billing.OrderView
                        ```
                        ```java
                        @Test
                        void findByReference_returnsTheOrderAsJsonOnItsMappedPath() throws Exception {
                            org.mockito.Mockito.when(orderService.findByReference("REF-1"))
                                    .thenReturn(java.util.Optional.of(new OrderView("REF-1", 4200L)));

                            mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                            .get("/api/orders/REF-1"))
                                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                                            .status().isOk())
                                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                                            .jsonPath("$.reference").value("REF-1"));
                        }
                        ```
                        """)
                .respondTo("No test issues a POST request to /api/orders", """
                        ```java
                        @Test
                        void create_bindsTheRequestBodyAndReturnsCreated() throws Exception {
                            org.mockito.Mockito.when(orderService.create("REF-2", 999L))
                                    .thenReturn(new com.acme.billing.OrderView("REF-2", 999L));

                            mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                            .post("/api/orders")
                                            .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                                            .content(new com.fasterxml.jackson.databind.ObjectMapper()
                                                    .writeValueAsString(new CreateOrderRequest("REF-2", 999L))))
                                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                                            .status().isCreated())
                                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                                            .jsonPath("$.reference").value("REF-2"));
                        }
                        ```
                        """);
    }
}
