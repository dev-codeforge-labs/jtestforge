package com.devmanchego.jtestforge.orchestration;

import com.devmanchego.jtestforge.config.ExecutionConfig;
import com.devmanchego.jtestforge.model.ContextKey;
import com.devmanchego.jtestforge.model.Phase;
import com.devmanchego.jtestforge.model.RunState;
import com.devmanchego.jtestforge.model.SpringTierState;
import com.devmanchego.jtestforge.model.Tier;
import com.devmanchego.jtestforge.model.UnitStatus;
import com.devmanchego.jtestforge.model.WorkUnit;
import com.devmanchego.jtestforge.model.WorkUnitId;
import com.devmanchego.jtestforge.spring.ContextKeyStabilityTracker;
import com.devmanchego.jtestforge.state.StateStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The engine's own responsibilities: preflight, run limits, state transitions, and §9.5's
 * final verification. The per-unit loop is {@link UnitProcessor}'s, and is scripted here
 * through a stub so each of these can be exercised without a real generation.
 */
class GenerateEngineTest {

    private static final Instant NOW = Instant.parse("2026-08-31T10:00:00Z");

    @Test
    void aRunThatKeepsSomethingCompletesAndPersistsDone(@TempDir Path stateDir) {
        StubUnitProcessor processor = new StubUnitProcessor().thenKeeps("newTest");
        FakeModuleBuild build = new FakeModuleBuild().fullSuiteGreen(2);
        StateStore store = store(stateDir);

        GenerateResult result = engine(processor, build, store).run(stateWithUnits(1), contextLookup());

        assertThat(result.exitReason()).isEqualTo(GenerateResult.ExitReason.COMPLETED);
        assertThat(store.load().orElseThrow().units().get(0).status()).isEqualTo(UnitStatus.DONE);
        assertThat(store.load().orElseThrow().units().get(0).addedTests()).containsExactly("newTest");
    }

    @Test
    void aModuleAlreadyFailingBeforeTheRunIsRefusedWithoutGeneratingAnything(@TempDir Path stateDir) {
        // §2: generating against a red suite makes every later "did this test pass?"
        // answer meaningless.
        StubUnitProcessor processor = new StubUnitProcessor().thenKeeps("newTest");
        FakeModuleBuild build = new FakeModuleBuild().fullSuiteRed();

        GenerateResult result = engine(processor, build, store(stateDir)).run(stateWithUnits(1), contextLookup());

        assertThat(result.exitReason()).isEqualTo(GenerateResult.ExitReason.PREFLIGHT_FAILED);
        assertThat(result.fullSuiteFailures()).isNotEmpty();
        assertThat(processor.invocations()).isZero();
    }

    /**
     * A module that does not compile produces NO Surefire reports, so judging the preflight
     * on reports alone reads as "nothing failed". That is how a broken module used to reach
     * the loop, after which every generated test was blamed for a compilation error that
     * predated the run - at minutes of AI time per unit.
     */
    @Test
    void aModuleThatDoesNotBuildIsRefusedBeforeGeneratingAnything(@TempDir Path stateDir) {
        StubUnitProcessor processor = new StubUnitProcessor().thenKeeps("newTest");
        FakeModuleBuild build = new FakeModuleBuild().fullSuiteBuildFails("""
                [INFO] Compiling 127 source files
                [ERROR] MarcheToMarcheEntityMapperTest.java:[130,13] reference to assertEquals is ambiguous
                [ERROR] -> [Help 1]
                """);

        GenerateResult result = engine(processor, build, store(stateDir)).run(stateWithUnits(1), contextLookup());

        assertThat(result.exitReason()).isEqualTo(GenerateResult.ExitReason.PREFLIGHT_FAILED);
        assertThat(processor.invocations()).isZero();
        assertThat(result.fullSuiteFailures())
                .anySatisfy(line -> assertThat(line).contains("assertEquals is ambiguous"))
                .noneSatisfy(line -> assertThat(line).contains("[Help 1]"));
    }

    @Test
    void aBuildThatBreaksDuringTheRunIsReportedAtTheEnd(@TempDir Path stateDir) {
        StubUnitProcessor processor = new StubUnitProcessor().thenKeeps("newTest");
        FakeModuleBuild build = new FakeModuleBuild()
                .fullSuiteGreen(1)
                .fullSuiteBuildFails("[ERROR] something stopped compiling");

        GenerateResult result = engine(processor, build, store(stateDir)).run(stateWithUnits(1), contextLookup());

        assertThat(result.exitReason()).isEqualTo(GenerateResult.ExitReason.FULL_SUITE_RED);
        assertThat(result.fullSuiteFailures())
                .anySatisfy(line -> assertThat(line).contains("stopped compiling"));
    }

    @Test
    void aRunWhereEveryUnitFailsReportsThatNothingWasKept(@TempDir Path stateDir) {
        StubUnitProcessor processor = new StubUnitProcessor()
                .thenFails(UnitStatus.DISCARDED_NO_VALUE, "no value")
                .thenFails(UnitStatus.FAILED_COMPILE, "never compiled");
        StateStore store = store(stateDir);

        GenerateResult result = engine(processor, new FakeModuleBuild().fullSuiteGreen(2), store)
                .run(stateWithUnits(2), contextLookup());

        assertThat(result.exitReason()).isEqualTo(GenerateResult.ExitReason.NOTHING_KEPT);
        assertThat(store.load().orElseThrow().units()).extracting(WorkUnit::status)
                .containsExactly(UnitStatus.DISCARDED_NO_VALUE, UnitStatus.FAILED_COMPILE);
    }

    @Test
    void aUnitIsNeverLeftInProgressAfterAnUnexpectedException(@TempDir Path stateDir) {
        // An IN_PROGRESS unit left behind by a normal return would look, on the next run,
        // exactly like one killed mid-generation - and resume would revert file edits that
        // were never made (§8.2.1).
        StubUnitProcessor processor = new StubUnitProcessor().thenThrows(new IllegalStateException("boom"));
        StateStore store = store(stateDir);

        GenerateResult result = engine(processor, new FakeModuleBuild().fullSuiteGreen(2), store)
                .run(stateWithUnits(1), contextLookup());

        WorkUnit unit = store.load().orElseThrow().units().get(0);
        assertThat(unit.status()).isNotEqualTo(UnitStatus.IN_PROGRESS);
        assertThat(unit.status()).isEqualTo(UnitStatus.PROVIDER_ERROR);
        assertThat(unit.lastError()).contains("boom");
        // Every attempted unit ended PROVIDER_ERROR - exit 4, not the more general "3:
        // nothing kept" (§14.1's own distinction).
        assertThat(result.exitReason()).isEqualTo(GenerateResult.ExitReason.PROVIDER_UNREACHABLE);
    }

    @Test
    void everyUnitIsMarkedInProgressBeforeItIsProcessed(@TempDir Path stateDir) {
        // The write-ahead marker (§8.2.1) must be on disk while the unit runs, not merely
        // in memory - that is what makes a crash mid-unit distinguishable afterwards.
        StateStore store = store(stateDir);
        StubUnitProcessor processor = new StubUnitProcessor()
                .observingStateAtInvocation(() -> store.load().orElseThrow().units().get(0).status())
                .thenKeeps("newTest");

        engine(processor, new FakeModuleBuild().fullSuiteGreen(2), store)
                .run(stateWithUnits(1), contextLookup());

        assertThat(processor.observedStatuses()).containsExactly(UnitStatus.IN_PROGRESS);
    }

    @Test
    void theRunAbortsAtExactlyTheConsecutiveFailureThreshold(@TempDir Path stateDir) {
        StubUnitProcessor processor = new StubUnitProcessor()
                .thenFails(UnitStatus.DISCARDED_NO_VALUE, "no value")
                .thenFails(UnitStatus.DISCARDED_NO_VALUE, "no value")
                .thenFails(UnitStatus.DISCARDED_NO_VALUE, "no value")
                .thenKeeps("wouldHaveWorked");

        GenerateResult result = engineWithAbortAfter(3, processor, new FakeModuleBuild().fullSuiteGreen(2),
                store(stateDir)).run(stateWithUnits(4), contextLookup());

        assertThat(result.exitReason())
                .isEqualTo(GenerateResult.ExitReason.ABORTED_ON_CONSECUTIVE_FAILURES);
        assertThat(processor.invocations()).isEqualTo(3);
    }

    @Test
    void aSuccessResetsTheConsecutiveFailureCount(@TempDir Path stateDir) {
        StubUnitProcessor processor = new StubUnitProcessor()
                .thenFails(UnitStatus.DISCARDED_NO_VALUE, "no value")
                .thenFails(UnitStatus.DISCARDED_NO_VALUE, "no value")
                .thenKeeps("brokeTheStreak")
                .thenFails(UnitStatus.DISCARDED_NO_VALUE, "no value")
                .thenFails(UnitStatus.DISCARDED_NO_VALUE, "no value");

        GenerateResult result = engineWithAbortAfter(3, processor, new FakeModuleBuild().fullSuiteGreen(2),
                store(stateDir)).run(stateWithUnits(5), contextLookup());

        assertThat(result.exitReason()).isEqualTo(GenerateResult.ExitReason.COMPLETED);
        assertThat(processor.invocations()).isEqualTo(5);
    }

    @Test
    void maxUnitsPerRunStopsTheLoopEarlyAndLeavesTheRestPending(@TempDir Path stateDir) {
        StubUnitProcessor processor = new StubUnitProcessor()
                .thenKeeps("first").thenKeeps("second").thenKeeps("third");
        StateStore store = store(stateDir);

        engineWithMaxUnits(2, processor, new FakeModuleBuild().fullSuiteGreen(2), store)
                .run(stateWithUnits(3), contextLookup());

        assertThat(processor.invocations()).isEqualTo(2);
        assertThat(store.load().orElseThrow().units()).extracting(WorkUnit::status)
                .containsExactly(UnitStatus.DONE, UnitStatus.DONE, UnitStatus.PENDING);
    }

    @Test
    void aFullSuiteThatTurnsRedAfterTheRunIsReportedLoudly(@TempDir Path stateDir) {
        // Every unit passed in isolation, so a red suite here means the new tests interact
        // - shared state or ordering - which a scoped run structurally cannot see (§9.5).
        StubUnitProcessor processor = new StubUnitProcessor().thenKeeps("newTest");
        FakeModuleBuild build = new FakeModuleBuild().fullSuiteGreen(1).fullSuiteRed();

        GenerateResult result = engine(processor, build, store(stateDir))
                .run(stateWithUnits(1), contextLookup());

        assertThat(result.exitReason()).isEqualTo(GenerateResult.ExitReason.FULL_SUITE_RED);
        assertThat(result.fullSuiteFailures()).isNotEmpty();
    }

    @Test
    void aRunWithNoUnitsAtAllCompletesRatherThanReportingNothingKept(@TempDir Path stateDir) {
        // "Nothing to do" is a success (§14.1 exit code 0), not a failure to produce.
        GenerateResult result = engine(new StubUnitProcessor(), new FakeModuleBuild().fullSuiteGreen(2),
                store(stateDir)).run(stateWithUnits(0), contextLookup());

        assertThat(result.exitReason()).isEqualTo(GenerateResult.ExitReason.COMPLETED);
    }

    @Test
    void unitsAlreadyTerminalFromAnEarlierRunAreNotReprocessed(@TempDir Path stateDir) {
        RunState state = stateWithUnits(2);
        RunState withOneDone = state.withUnit(state.units().get(0).withStatus(UnitStatus.DONE));
        StubUnitProcessor processor = new StubUnitProcessor().thenKeeps("second");

        engine(processor, new FakeModuleBuild().fullSuiteGreen(2), store(stateDir))
                .run(withOneDone, contextLookup());

        assertThat(processor.invocations()).isEqualTo(1);
    }

    // --- §9.6: tier ordering, class grouping, and the context-load budget -------------

    @Test
    void everyPlainUnitRunsBeforeAnyWebSliceUnit(@TempDir Path stateDir) {
        List<WorkUnitId> processedOrder = new ArrayList<>();
        RunState state = mixedTierState();
        StubUnitProcessor processor = new StubUnitProcessor()
                .thenKeeps("web1").thenKeeps("plain1").thenKeeps("plain2");

        engine(processor, new FakeModuleBuild().fullSuiteGreen(2), store(stateDir))
                .run(state, recordingContextLookup(processedOrder));

        assertThat(processedOrder).extracting(WorkUnitId::tier)
                .containsExactly(Tier.PLAIN_UNIT, Tier.PLAIN_UNIT, Tier.WEB_SLICE);
    }

    @Test
    void fiveUnitsSharingOneClassKeyCostExactlyOneContextLoad(@TempDir Path stateDir) {
        ContextKeyStabilityTracker tracker = new ContextKeyStabilityTracker(40);
        ContextKey sharedKey = webSliceKey();
        RunState state = webSliceState(5);
        StubUnitProcessor processor = new StubUnitProcessor();
        for (int i = 0; i < 5; i++) {
            processor.thenKeeps("t" + i, () -> tracker.record("com.acme.web.OrderControllerWebTest", sharedKey));
        }

        GenerateResult result = engineWithContextTracker(processor, new FakeModuleBuild().fullSuiteGreen(2),
                store(stateDir), tracker).run(state, contextLookup());

        assertThat(result.exitReason()).isEqualTo(GenerateResult.ExitReason.COMPLETED);
        assertThat(tracker.contextLoads()).isEqualTo(1);
    }

    @Test
    void exceedingTheContextLoadBudgetAbortsWithExitCode8AndNamesTheOffender(@TempDir Path stateDir) {
        ContextKeyStabilityTracker tracker = new ContextKeyStabilityTracker(2);
        ContextKey original = webSliceKey();
        ContextKey forked = new ContextKey(Tier.WEB_SLICE, List.of("OrderController"), List.of(), List.of(),
                java.util.Set.of("AuditLog"), "", List.of());
        RunState state = webSliceState(2);
        StubUnitProcessor processor = new StubUnitProcessor()
                .thenKeeps("t0", () -> tracker.record("com.acme.web.OrderControllerWebTest", original))
                .thenKeeps("t1", () -> tracker.record("com.acme.web.OrderControllerWebTest", forked));

        GenerateResult result = engineWithContextTracker(processor, new FakeModuleBuild().fullSuiteGreen(2),
                store(stateDir), tracker).run(state, contextLookup());

        assertThat(result.exitReason()).isEqualTo(GenerateResult.ExitReason.CONTEXT_LOAD_BUDGET_EXHAUSTED);
        assertThat(result.contextForkedClasses()).containsExactly("com.acme.web.OrderControllerWebTest");
    }

    @Test
    void tierPlainUnitRestrictionLoadsNoContextAtAll(@TempDir Path stateDir) {
        ContextKeyStabilityTracker tracker = new ContextKeyStabilityTracker(40);
        RunState state = mixedTierState();
        StubUnitProcessor processor = new StubUnitProcessor()
                .thenKeeps("plain1", () -> { })
                .thenKeeps("plain2", () -> { });

        engineWithContextTracker(processor, new FakeModuleBuild().fullSuiteGreen(2), store(stateDir), tracker)
                .run(state, contextLookup(), TierRestriction.noSpring());

        assertThat(processor.invocations()).isEqualTo(2);
        assertThat(tracker.contextLoads()).isZero();
    }

    @Test
    void withAnUnavailableTierEveryUnitAtThatTierIsSkippedAndOtherTiersAreUnaffected(@TempDir Path stateDir) {
        // e.g. H2 removed from the test classpath: every DATA_SLICE unit is
        // SKIPPED_TIER_UNAVAILABLE and PLAIN_UNIT units are untouched.
        StateStore store = store(stateDir);
        RunState state = mixedPlainAndDataSliceStateWithDataSliceUnavailable();
        StubUnitProcessor processor = new StubUnitProcessor().thenKeeps("plain1").thenKeeps("plain2");

        engine(processor, new FakeModuleBuild().fullSuiteGreen(2), store).run(state, contextLookup());

        RunState finalState = store.load().orElseThrow();
        assertThat(finalState.units()).filteredOn(unit -> unit.tier() == Tier.DATA_SLICE)
                .extracting(WorkUnit::status).containsOnly(UnitStatus.SKIPPED_TIER_UNAVAILABLE);
        assertThat(finalState.units()).filteredOn(unit -> unit.tier() == Tier.DATA_SLICE)
                .extracting(WorkUnit::skipReason).containsOnly("no embedded database on the test classpath");
        assertThat(finalState.units()).filteredOn(unit -> unit.tier() == Tier.PLAIN_UNIT)
                .extracting(WorkUnit::status).containsOnly(UnitStatus.DONE);
        assertThat(processor.invocations()).isEqualTo(2);
    }

    @Test
    void progressReportsEachUnitsStartWithAnIndexAndItsOutcomeAtTheEnd(@TempDir Path stateDir) {
        List<String> progress = new ArrayList<>();
        StubUnitProcessor processor = new StubUnitProcessor()
                .thenKeeps("newTest")
                .thenFails(UnitStatus.DISCARDED_NO_VALUE, "no value");

        engineWithProgress(processor, new FakeModuleBuild().fullSuiteGreen(2), store(stateDir), progress::add)
                .run(stateWithUnits(2), contextLookup());

        assertThat(progress).anySatisfy(line -> assertThat(line).contains("[1/2]"));
        assertThat(progress).anySatisfy(line -> assertThat(line).contains("[2/2]"));
        assertThat(progress).anySatisfy(line -> assertThat(line).contains("DONE"));
        assertThat(progress).anySatisfy(line -> assertThat(line).contains("DISCARDED_NO_VALUE"));
    }

    // --- fixtures ---------------------------------------------------------------------

    private GenerateEngine engine(UnitProcessor processor, FakeModuleBuild build, StateStore store) {
        return new GenerateEngine(processor, build, store,
                new ExecutionConfig(null, null, null, null, null));
    }

    private GenerateEngine engineWithAbortAfter(
            int threshold, UnitProcessor processor, FakeModuleBuild build, StateStore store) {
        return new GenerateEngine(processor, build, store,
                new ExecutionConfig(null, null, null, threshold, null));
    }

    private GenerateEngine engineWithMaxUnits(
            int maxUnits, UnitProcessor processor, FakeModuleBuild build, StateStore store) {
        return new GenerateEngine(processor, build, store,
                new ExecutionConfig(null, null, null, null, maxUnits));
    }

    private GenerateEngine engineWithContextTracker(UnitProcessor processor, FakeModuleBuild build,
                                                     StateStore store, ContextKeyStabilityTracker tracker) {
        return new GenerateEngine(processor, build, store,
                new ExecutionConfig(null, null, null, null, null), tracker);
    }

    private GenerateEngine engineWithProgress(UnitProcessor processor, FakeModuleBuild build, StateStore store,
                                              java.util.function.Consumer<String> progress) {
        return new GenerateEngine(processor, build, store,
                new ExecutionConfig(null, null, null, null, null),
                new ContextKeyStabilityTracker(Integer.MAX_VALUE), progress);
    }

    private StateStore store(Path stateDir) {
        return new StateStore(stateDir, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private RunState stateWithUnits(int count) {
        List<WorkUnit> units = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            units.add(WorkUnit.pending(
                    WorkUnitId.of("com.acme.PaymentService", "method" + i + "()", Tier.PLAIN_UNIT),
                    "src/test/java/com/acme/PaymentServiceTest.java",
                    "src/main/java/com/acme/PaymentService.java", "sha256:src"));
        }
        return RunState.startNew("run-1", NOW, Phase.GENERATE, "C:/app", "sha256:cfg", "claude",
                SpringTierState.springDisabled(40), units);
    }

    private RunState mixedTierState() {
        List<WorkUnit> units = List.of(
                WorkUnit.pending(WorkUnitId.of("com.acme.web.OrderController", "findById()", Tier.WEB_SLICE),
                        "src/test/java/com/acme/web/OrderControllerWebTest.java",
                        "src/main/java/com/acme/web/OrderController.java", "sha256:src"),
                WorkUnit.pending(WorkUnitId.of("com.acme.PaymentService", "applyFee()", Tier.PLAIN_UNIT),
                        "src/test/java/com/acme/PaymentServiceTest.java",
                        "src/main/java/com/acme/PaymentService.java", "sha256:src"),
                WorkUnit.pending(WorkUnitId.of("com.acme.PaymentService", "settle()", Tier.PLAIN_UNIT),
                        "src/test/java/com/acme/PaymentServiceTest.java",
                        "src/main/java/com/acme/PaymentService.java", "sha256:src"));
        SpringTierState springTiers = new SpringTierState(
                java.util.Set.of(Tier.PLAIN_UNIT, Tier.WEB_SLICE), java.util.Map.of(), 0, 40);
        return RunState.startNew("run-1", NOW, Phase.GENERATE, "C:/app", "sha256:cfg", "claude",
                springTiers, units);
    }

    private RunState webSliceState(int count) {
        List<WorkUnit> units = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            units.add(WorkUnit.pending(
                    WorkUnitId.of("com.acme.web.OrderController", "handler" + i + "()", Tier.WEB_SLICE),
                    "src/test/java/com/acme/web/OrderControllerWebTest.java",
                    "src/main/java/com/acme/web/OrderController.java", "sha256:src"));
        }
        SpringTierState springTiers = new SpringTierState(
                java.util.Set.of(Tier.PLAIN_UNIT, Tier.WEB_SLICE), java.util.Map.of(), 0, 40);
        return RunState.startNew("run-1", NOW, Phase.GENERATE, "C:/app", "sha256:cfg", "claude",
                springTiers, units);
    }

    private RunState mixedPlainAndDataSliceStateWithDataSliceUnavailable() {
        List<WorkUnit> units = List.of(
                WorkUnit.pending(WorkUnitId.of("com.acme.PaymentService", "applyFee()", Tier.PLAIN_UNIT),
                        "src/test/java/com/acme/PaymentServiceTest.java",
                        "src/main/java/com/acme/PaymentService.java", "sha256:src"),
                WorkUnit.pending(WorkUnitId.of("com.acme.PaymentService", "settle()", Tier.PLAIN_UNIT),
                        "src/test/java/com/acme/PaymentServiceTest.java",
                        "src/main/java/com/acme/PaymentService.java", "sha256:src"),
                WorkUnit.pending(WorkUnitId.of("com.acme.OrderRepository", "findByRef()", Tier.DATA_SLICE),
                        "src/test/java/com/acme/OrderRepositoryDataTest.java",
                        "src/main/java/com/acme/OrderRepository.java", "sha256:src"));
        SpringTierState springTiers = new SpringTierState(java.util.Set.of(Tier.PLAIN_UNIT),
                java.util.Map.of(Tier.DATA_SLICE, "no embedded database on the test classpath"), 0, 40);
        return RunState.startNew("run-1", NOW, Phase.GENERATE, "C:/app", "sha256:cfg", "claude",
                springTiers, units);
    }

    private ContextKey webSliceKey() {
        return new ContextKey(Tier.WEB_SLICE, List.of("OrderController"), List.of(), List.of(),
                java.util.Set.of(), "", List.of());
    }

    /** The engine never inspects the context; it only hands it to the processor. */
    private Function<WorkUnit, UnitContext> contextLookup() {
        return unit -> null;
    }

    /** Records processing order by unit id, still handing the processor a null context. */
    private Function<WorkUnit, UnitContext> recordingContextLookup(List<WorkUnitId> processedOrder) {
        return unit -> {
            processedOrder.add(unit.id());
            return null;
        };
    }

    /** A {@link UnitProcessor} whose outcomes are scripted rather than generated. */
    private static final class StubUnitProcessor implements UnitProcessor {
        private final Deque<ScriptedStep> scripted = new ArrayDeque<>();
        private final List<UnitStatus> observedStatuses = new ArrayList<>();
        private java.util.function.Supplier<UnitStatus> observer;
        private int invocations;

        StubUnitProcessor thenKeeps(String testName) {
            return thenKeeps(testName, () -> { });
        }

        /**
         * Kept, additionally running {@code sideEffect} - the hook the context-load-budget
         * tests use to simulate what {@code DefaultUnitProcessor} would really do: record
         * a context key against a shared {@code ContextKeyStabilityTracker} while
         * processing a Spring-tier unit.
         */
        StubUnitProcessor thenKeeps(String testName, Runnable sideEffect) {
            scripted.add(new ScriptedStep(
                    UnitOutcome.kept(List.of(testName), List.of(), List.of(), List.of(), 1, 0), sideEffect));
            return this;
        }

        StubUnitProcessor thenFails(UnitStatus status, String error) {
            scripted.add(new ScriptedStep(UnitOutcome.failed(status, error, List.of()), () -> { }));
            return this;
        }

        StubUnitProcessor thenThrows(RuntimeException exception) {
            scripted.add(new ScriptedStep(exception, () -> { }));
            return this;
        }

        StubUnitProcessor observingStateAtInvocation(java.util.function.Supplier<UnitStatus> observer) {
            this.observer = observer;
            return this;
        }

        @Override
        public UnitOutcome process(UnitContext context) {
            invocations++;
            if (observer != null) {
                observedStatuses.add(observer.get());
            }
            ScriptedStep next = scripted.poll();
            if (next == null) {
                return UnitOutcome.failed(UnitStatus.DISCARDED_NO_VALUE, "unscripted", List.of());
            }
            next.sideEffect().run();
            if (next.outcomeOrException() instanceof RuntimeException exception) {
                throw exception;
            }
            return (UnitOutcome) next.outcomeOrException();
        }

        int invocations() {
            return invocations;
        }

        List<UnitStatus> observedStatuses() {
            return List.copyOf(observedStatuses);
        }

        private record ScriptedStep(Object outcomeOrException, Runnable sideEffect) {
        }
    }
}
