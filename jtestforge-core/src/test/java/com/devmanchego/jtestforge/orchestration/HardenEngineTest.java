package com.devmanchego.jtestforge.orchestration;

import com.devmanchego.jtestforge.config.HardenConfig;
import com.devmanchego.jtestforge.model.Mutant;
import com.devmanchego.jtestforge.model.MutationReport;
import com.devmanchego.jtestforge.model.MutationStatus;
import com.devmanchego.jtestforge.model.Phase;
import com.devmanchego.jtestforge.model.RunState;
import com.devmanchego.jtestforge.model.SpringTierState;
import com.devmanchego.jtestforge.model.Tier;
import com.devmanchego.jtestforge.model.UnitStatus;
import com.devmanchego.jtestforge.model.WorkUnit;
import com.devmanchego.jtestforge.model.WorkUnitId;
import com.devmanchego.jtestforge.mutation.UnkillableMutantStore;
import com.devmanchego.jtestforge.state.StateStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * §10.1's flow and §10.3's tier restriction. The per-unit loop is {@link UnitProcessor}'s
 * (real end-to-end coverage of the mutant-kill acceptance path lives in
 * {@code DefaultUnitProcessorTest}); this class exercises what {@link HardenEngine} adds
 * on top of it: the baseline run, tier-restricted scoping, the per-mutant retry budget,
 * and promotion to the unkillable list.
 */
class HardenEngineTest {

    private static final Instant NOW = Instant.parse("2026-08-31T10:00:00Z");
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    // --- §10.3 tier restriction, as pure logic -----------------------------------------

    @Test
    void withTheDefaultMaxTierForMutationTargetsContainNoSliceClass(@TempDir Path dir) {
        HardenEngine engine = engine(dir, hardenConfig(Tier.PLAIN_UNIT), new FakeMutationRunner(),
                new ScriptedUnitProcessor());
        List<TierScopedTestClass> classes = List.of(
                new TierScopedTestClass("com.acme.PaymentService", "com.acme.PaymentServiceTest", Tier.PLAIN_UNIT),
                new TierScopedTestClass("com.acme.web.OrderController", "com.acme.web.OrderControllerWebTest", Tier.WEB_SLICE),
                new TierScopedTestClass("com.acme.OrderRepository", "com.acme.OrderRepositoryDataTest", Tier.DATA_SLICE));

        assertThat(engine.restrictedTargetClasses(classes)).containsExactly("com.acme.PaymentService");
        assertThat(engine.restrictedTargetTests(classes)).containsExactly("com.acme.PaymentServiceTest");
    }

    @Test
    void raisingMaxTierForMutationToDataSliceIncludesT0ThroughT2AndStillExcludesT3AndT4(@TempDir Path dir) {
        // §10.3: "at or below" is ordinal - Tier's own declared order is cheapest-first
        // (T0..T4), so raising the ceiling to T2 pulls in every cheaper tier too, not just
        // T2 in isolation. It never reaches T3/T4, the two genuinely more expensive tiers.
        HardenEngine engine = engine(dir, hardenConfig(Tier.DATA_SLICE), new FakeMutationRunner(),
                new ScriptedUnitProcessor());
        List<TierScopedTestClass> classes = List.of(
                new TierScopedTestClass("com.acme.PaymentService", "com.acme.PaymentServiceTest", Tier.PLAIN_UNIT),
                new TierScopedTestClass("com.acme.web.OrderController", "com.acme.web.OrderControllerWebTest", Tier.WEB_SLICE),
                new TierScopedTestClass("com.acme.OrderRepository", "com.acme.OrderRepositoryDataTest", Tier.DATA_SLICE),
                new TierScopedTestClass("com.acme.config.AppProperties", "com.acme.config.AppPropertiesJsonTest", Tier.JSON_SLICE),
                new TierScopedTestClass("com.acme.config.CacheConfig", "com.acme.config.CacheConfigContextTest", Tier.CONTEXT_SLICE));

        assertThat(engine.restrictedTargetClasses(classes)).containsExactlyInAnyOrder(
                "com.acme.PaymentService", "com.acme.web.OrderController", "com.acme.OrderRepository");
        assertThat(engine.restrictedTargetTests(classes)).containsExactlyInAnyOrder(
                "com.acme.PaymentServiceTest", "com.acme.web.OrderControllerWebTest", "com.acme.OrderRepositoryDataTest");
    }

    // --- §10.1 flow ----------------------------------------------------------------------

    @Test
    void aRedSuiteBeforeTheRunIsRefusedWithoutRunningPit(@TempDir Path dir) {
        FakeModuleBuild build = new FakeModuleBuild().fullSuiteRed();
        FakeMutationRunner mutationRunner = new FakeMutationRunner();
        HardenEngine engine = engine(dir, build, hardenConfig(Tier.PLAIN_UNIT), mutationRunner,
                new ScriptedUnitProcessor());

        HardenResult result = engine.run(freshState(), List.of(plainUnitClass()), group -> null);

        assertThat(result.exitReason()).isEqualTo(HardenResult.ExitReason.PREFLIGHT_FAILED);
        assertThat(mutationRunner.requests()).isEmpty();
    }

    @Test
    void withNoEligibleClassAtAllTheRunCompletesWithoutCallingPit(@TempDir Path dir) {
        FakeMutationRunner mutationRunner = new FakeMutationRunner();
        HardenEngine engine = engine(dir, new FakeModuleBuild().fullSuiteGreen(2), hardenConfig(Tier.PLAIN_UNIT),
                mutationRunner, new ScriptedUnitProcessor());

        HardenResult result = engine.run(freshState(), List.of(), group -> null);

        assertThat(result.exitReason()).isEqualTo(HardenResult.ExitReason.COMPLETED);
        assertThat(mutationRunner.requests()).isEmpty();
    }

    @Test
    void aGroupKilledOnTheFirstAttemptStopsRetryingAndIsMarkedDone(@TempDir Path dir) {
        FakeMutationRunner mutationRunner = new FakeMutationRunner()
                .thenReturns(new MutationReport(List.of(survivingMutant("classify", 10, "MathMutator"))));
        ScriptedUnitProcessor processor = new ScriptedUnitProcessor().thenKeeps("killsTheMutant");
        StateStore store = store(dir);
        HardenEngine engine = engine(dir, store, new FakeModuleBuild().fullSuiteGreen(2),
                hardenConfig(Tier.PLAIN_UNIT, 3), mutationRunner, processor);

        HardenResult result = engine.run(freshState(),
                List.of(plainUnitClass()), contextLookupFor(List.of(survivingMutant("classify", 10, "MathMutator"))));

        assertThat(result.exitReason()).isEqualTo(HardenResult.ExitReason.COMPLETED);
        assertThat(processor.invocations()).isEqualTo(1);
        assertThat(store.load().orElseThrow().units()).extracting(WorkUnit::status).containsExactly(UnitStatus.DONE);
    }

    @Test
    void aGroupThatNeverKillsAnythingExhaustsMaxAttemptsAndPromotesEveryMutantToUnkillable(@TempDir Path dir) {
        Mutant equivalentMutant = survivingMutant("classify", 10, "MathMutator");
        FakeMutationRunner mutationRunner = new FakeMutationRunner()
                .thenReturns(new MutationReport(List.of(equivalentMutant)));
        ScriptedUnitProcessor processor = new ScriptedUnitProcessor()
                .thenFails("no mutant killed").thenFails("no mutant killed").thenFails("no mutant killed");
        StateStore store = store(dir);
        UnkillableMutantStore unkillableStore = new UnkillableMutantStore(dir.resolve("unkillable.txt"));
        HardenEngine engine = engine(dir, store, unkillableStore, new FakeModuleBuild().fullSuiteGreen(2),
                hardenConfig(Tier.PLAIN_UNIT, 3), mutationRunner, processor);

        HardenResult result = engine.run(freshState(), List.of(plainUnitClass()),
                contextLookupFor(List.of(equivalentMutant)));

        assertThat(processor.invocations()).isEqualTo(3);
        assertThat(result.exitReason()).isEqualTo(HardenResult.ExitReason.NOTHING_KEPT);
        assertThat(store.load().orElseThrow().units())
                .extracting(WorkUnit::status).containsExactly(UnitStatus.SKIPPED_UNKILLABLE);
        assertThat(unkillableStore.contains(equivalentMutant.stableId())).isTrue();
    }

    @Test
    void anEquivalentMutantAlreadyOnTheUnkillableListIsAbsentFromTheNextRunsUnits(@TempDir Path dir) {
        // Simulates the "fixture's known equivalent mutant" test criterion: once a mutant
        // has exhausted its attempts and been promoted, a later run whose fresh baseline
        // still reports it as surviving must not attempt it again.
        Mutant equivalentMutant = survivingMutant("classify", 10, "MathMutator");
        UnkillableMutantStore unkillableStore = new UnkillableMutantStore(dir.resolve("unkillable.txt"));
        unkillableStore.append(equivalentMutant.stableId(), "maxAttemptsPerMutant exhausted");

        FakeMutationRunner mutationRunner = new FakeMutationRunner()
                .thenReturns(new MutationReport(List.of(equivalentMutant)));
        ScriptedUnitProcessor processor = new ScriptedUnitProcessor();
        HardenEngine engine = engine(dir, store(dir), unkillableStore, new FakeModuleBuild().fullSuiteGreen(2),
                hardenConfig(Tier.PLAIN_UNIT, 3), mutationRunner, processor);

        HardenResult result = engine.run(freshState(), List.of(plainUnitClass()),
                contextLookupFor(List.of(equivalentMutant)));

        assertThat(processor.invocations()).isZero();
        // The point of this test: the unit loop never even started for this mutant. The
        // baseline still contains it as the run's only mutant, still SURVIVED (nothing
        // killed it), so the final score is genuinely 0% - correctly below the default
        // harden.minMutationScore (70), not a side effect of anything this test is about.
        assertThat(result.exitReason()).isEqualTo(HardenResult.ExitReason.MUTATION_SCORE_BELOW_THRESHOLD);
    }

    @Test
    void aFullSuiteThatTurnsRedAfterTheRunIsReportedLoudly(@TempDir Path dir) {
        Mutant mutant = survivingMutant("classify", 10, "MathMutator");
        FakeMutationRunner mutationRunner = new FakeMutationRunner().thenReturns(new MutationReport(List.of(mutant)));
        ScriptedUnitProcessor processor = new ScriptedUnitProcessor().thenKeeps("killsIt");
        HardenEngine engine = engine(dir, store(dir), new FakeModuleBuild().fullSuiteGreen(1).fullSuiteRed(),
                hardenConfig(Tier.PLAIN_UNIT, 3), mutationRunner, processor);

        HardenResult result = engine.run(freshState(), List.of(plainUnitClass()), contextLookupFor(List.of(mutant)));

        assertThat(result.exitReason()).isEqualTo(HardenResult.ExitReason.FULL_SUITE_RED);
    }

    @Test
    void theBaselineRequestCarriesOnlyTheRestrictedClassesAndTheHistoryFile(@TempDir Path dir) {
        FakeMutationRunner mutationRunner = new FakeMutationRunner().thenReturns(new MutationReport(List.of()));
        HardenEngine engine = engine(dir, store(dir), new FakeModuleBuild().fullSuiteGreen(2),
                hardenConfig(Tier.PLAIN_UNIT, 3), mutationRunner, new ScriptedUnitProcessor());
        List<TierScopedTestClass> classes = List.of(plainUnitClass(),
                new TierScopedTestClass("com.acme.web.OrderController", "com.acme.web.OrderControllerWebTest", Tier.WEB_SLICE));

        engine.run(freshState(), classes, group -> null);

        assertThat(mutationRunner.requests()).hasSize(1);
        var request = mutationRunner.requests().get(0);
        assertThat(request.targetClasses()).containsExactly("com.acme.PaymentService");
        assertThat(request.targetTests()).containsExactly("com.acme.PaymentServiceTest");
        assertThat(request.historyFile()).isEqualTo(dir.resolve("pit-history.bin"));
    }

    @Test
    void whenTheBaselinePitRunItselfFailsTheRunReportsPreflightFailedWithoutTouchingUnits(@TempDir Path dir) {
        FakeMutationRunner mutationRunner = new FakeMutationRunner().thenFails("pitest-maven not resolvable");
        StateStore store = store(dir);
        HardenEngine engine = engine(dir, store, new FakeModuleBuild().fullSuiteGreen(2),
                hardenConfig(Tier.PLAIN_UNIT, 3), mutationRunner, new ScriptedUnitProcessor());

        HardenResult result = engine.run(freshState(), List.of(plainUnitClass()), group -> null);

        assertThat(result.exitReason()).isEqualTo(HardenResult.ExitReason.PREFLIGHT_FAILED);
        assertThat(result.details()).anySatisfy(detail -> assertThat(detail).contains("pitest-maven not resolvable"));
        assertThat(store.load().orElseThrow().units()).isEmpty();
    }

    @Test
    void everyAttemptedUnitFailingWithAProviderErrorReportsExitFourNotThree(@TempDir Path dir) {
        // §14.1's own distinction: exit 4 (provider unreachable) is more specific than the
        // general exit 3 ("nothing kept") and should be reported when it genuinely applies.
        Mutant mutant = survivingMutant("classify", 10, "MathMutator");
        FakeMutationRunner mutationRunner = new FakeMutationRunner().thenReturns(new MutationReport(List.of(mutant)));
        ScriptedUnitProcessor processor = new ScriptedUnitProcessor()
                .thenFailsWithProviderError("the CLI produced no output")
                .thenFailsWithProviderError("the CLI produced no output")
                .thenFailsWithProviderError("the CLI produced no output");
        HardenEngine engine = engine(dir, store(dir), new FakeModuleBuild().fullSuiteGreen(2),
                hardenConfig(Tier.PLAIN_UNIT, 3), mutationRunner, processor);

        HardenResult result = engine.run(freshState(), List.of(plainUnitClass()), contextLookupFor(List.of(mutant)));

        assertThat(result.exitReason()).isEqualTo(HardenResult.ExitReason.PROVIDER_UNREACHABLE);
    }

    @Test
    void aFinalMutationScoreBelowTheConfiguredThresholdIsReportedAsExitSeven(@TempDir Path dir) {
        Mutant killedThisRun = survivingMutant("classify", 10, "MathMutator");
        Mutant stillSurviving = survivingMutant("settle", 20, "ConditionalsBoundaryMutator");
        FakeMutationRunner mutationRunner = new FakeMutationRunner()
                .thenReturns(new MutationReport(List.of(killedThisRun, stillSurviving)));
        // Two groups: one killed (classify), one never attempted because its own group is
        // processed after - both share the same baseline, so the final score is 1 of 2 = 50%.
        ScriptedUnitProcessor processor = new ScriptedUnitProcessor().thenKeeps("killsIt").thenFails("no kill");
        StateStore store = store(dir);
        HardenEngine engine = engine(dir, store, new FakeModuleBuild().fullSuiteGreen(2),
                new HardenConfig(null, null, null, 6, 1, 90, Tier.PLAIN_UNIT, null, null),
                mutationRunner, processor);

        HardenResult result = engine.run(freshState(), List.of(plainUnitClass()),
                contextLookupFor(List.of(killedThisRun, stillSurviving)));

        assertThat(result.finalMutationScore()).isEqualTo(0.5);
        assertThat(result.exitReason()).isEqualTo(HardenResult.ExitReason.MUTATION_SCORE_BELOW_THRESHOLD);
    }

    // --- fixtures -----------------------------------------------------------------------

    private HardenEngine engine(Path dir, HardenConfig config, FakeMutationRunner mutationRunner,
                                ScriptedUnitProcessor processor) {
        return engine(dir, new FakeModuleBuild().fullSuiteGreen(2), config, mutationRunner, processor);
    }

    private HardenEngine engine(Path dir, FakeModuleBuild build, HardenConfig config,
                                FakeMutationRunner mutationRunner, ScriptedUnitProcessor processor) {
        return engine(dir, store(dir), build, config, mutationRunner, processor);
    }

    private HardenEngine engine(Path dir, StateStore store, FakeModuleBuild build, HardenConfig config,
                                FakeMutationRunner mutationRunner, ScriptedUnitProcessor processor) {
        return engine(dir, store, new UnkillableMutantStore(dir.resolve("unkillable.txt")), build, config,
                mutationRunner, processor);
    }

    private HardenEngine engine(Path dir, StateStore store, UnkillableMutantStore unkillableStore,
                                FakeModuleBuild build, HardenConfig config,
                                FakeMutationRunner mutationRunner, ScriptedUnitProcessor processor) {
        return new HardenEngine(processor, build, store, mutationRunner, unkillableStore, config,
                dir, dir.resolve("pit-history.bin"), TIMEOUT);
    }

    private StateStore store(Path dir) {
        return new StateStore(dir.resolve("state"), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private HardenConfig hardenConfig(Tier maxTier) {
        return hardenConfig(maxTier, 2);
    }

    private HardenConfig hardenConfig(Tier maxTier, int maxAttemptsPerMutant) {
        return new HardenConfig(null, null, null, 6, maxAttemptsPerMutant, null, maxTier, null, null);
    }

    private RunState freshState() {
        return RunState.startNew("run-1", NOW, Phase.HARDEN, "C:/app", "sha256:cfg", "claude",
                SpringTierState.springDisabled(40), List.of());
    }

    private TierScopedTestClass plainUnitClass() {
        return new TierScopedTestClass("com.acme.PaymentService", "com.acme.PaymentServiceTest", Tier.PLAIN_UNIT);
    }

    private Mutant survivingMutant(String method, int line, String mutatorSimpleName) {
        return new Mutant("com.acme.PaymentService", method, "(I)I", line,
                "org.pitest.mutationtest.engine.gregor.mutators." + mutatorSimpleName,
                List.of(1), MutationStatus.SURVIVED, null, "description");
    }

    /** Builds a minimal, valid {@link UnitContext} for one mutant group. */
    private Function<List<Mutant>, UnitContext> contextLookupFor(List<Mutant> expectedGroup) {
        return group -> {
            var method = new com.devmanchego.jtestforge.model.ProductionMethod(
                    group.get(0).mutatedMethod(), "int", List.of(),
                    com.devmanchego.jtestforge.model.Visibility.PUBLIC, false,
                    List.of(), java.util.Map.of(), List.of(), 1, 20, 2);
            var productionClass = new com.devmanchego.jtestforge.model.ProductionClass(
                    "com.acme.PaymentService", Path.of("PaymentService.java"), List.of(), List.of(),
                    List.of(), List.of(method));
            WorkUnitId id = WorkUnitId.of("com.acme.PaymentService", group.get(0).mutatedMethod() + "(I)I",
                    Tier.PLAIN_UNIT).withMutantGroup(group.get(0).mutator() + "@" + group.get(0).lineNumber());
            WorkUnit unit = WorkUnit.pending(id, "PaymentServiceTest.java", "PaymentService.java", "sha256:src");
            return new UnitContext(unit, productionClass, method, Path.of("PaymentServiceTest.java"),
                    "PaymentServiceTest", null, List.of(), null, null, null, List.of(), null, group);
        };
    }

    /** A {@link UnitProcessor} whose outcomes are scripted, recording every invocation. */
    private static final class ScriptedUnitProcessor implements UnitProcessor {
        private final Deque<UnitOutcome> scripted = new ArrayDeque<>();
        private final List<UnitContext> seenContexts = new ArrayList<>();

        ScriptedUnitProcessor thenKeeps(String testName) {
            scripted.add(UnitOutcome.kept(List.of(testName), List.of(), List.of(), List.of(),
                    List.of("mutant-killed"), 0, 0));
            return this;
        }

        ScriptedUnitProcessor thenFails(String reason) {
            scripted.add(UnitOutcome.failed(UnitStatus.DISCARDED_NO_VALUE, reason, List.of()));
            return this;
        }

        ScriptedUnitProcessor thenFailsWithProviderError(String reason) {
            scripted.add(UnitOutcome.failed(UnitStatus.PROVIDER_ERROR, reason, List.of()));
            return this;
        }

        @Override
        public UnitOutcome process(UnitContext context) {
            seenContexts.add(context);
            UnitOutcome next = scripted.poll();
            return next != null ? next : UnitOutcome.failed(UnitStatus.DISCARDED_NO_VALUE, "unscripted", List.of());
        }

        int invocations() {
            return seenContexts.size();
        }
    }
}
