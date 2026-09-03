package com.devmanchego.jtestforge.orchestration;

import com.devmanchego.jtestforge.build.ModuleBuild;
import com.devmanchego.jtestforge.build.TestRunOutcome;
import com.devmanchego.jtestforge.config.ExecutionConfig;
import com.devmanchego.jtestforge.model.RunState;
import com.devmanchego.jtestforge.model.SurefireTestResult;
import com.devmanchego.jtestforge.model.UnitStatus;
import com.devmanchego.jtestforge.model.WorkUnit;
import com.devmanchego.jtestforge.model.WorkUnitId;
import com.devmanchego.jtestforge.spring.ContextKeyStabilityTracker;
import com.devmanchego.jtestforge.state.StateStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * Pass 1 — jtestforge-specification.md §9.
 *
 * <p>Preflight, then the per-unit loop, then the final full-suite verification. The loop
 * body itself belongs to {@link UnitProcessor}; this class owns everything <em>around</em>
 * it: the write-ahead marker, the run limits, tier ordering and the Spring context-load
 * budget (§9.6), and the guarantee that a unit is never left {@code IN_PROGRESS}.
 *
 * <p>That last guarantee is the reason every unit is processed inside a try/catch that
 * cannot escape without persisting a terminal status. An {@code IN_PROGRESS} unit left
 * behind by a normal return would be indistinguishable, on the next run, from one killed
 * mid-generation - and resume would revert file edits that were never made (§8.2.1).
 */
public final class GenerateEngine {

    private static final Logger LOGGER = LoggerFactory.getLogger(GenerateEngine.class);

    /** Statuses meaning a unit was genuinely attempted this run, not skipped or pending. */
    private static final Set<UnitStatus> ATTEMPTED_STATUSES = Set.of(UnitStatus.DONE,
            UnitStatus.FAILED_COMPILE, UnitStatus.FAILED_ASSERTION, UnitStatus.DISCARDED_NO_VALUE,
            UnitStatus.PROVIDER_ERROR);

    private final UnitProcessor unitProcessor;
    private final ModuleBuild moduleBuild;
    private final StateStore stateStore;
    private final ExecutionConfig executionConfig;
    private final ContextKeyStabilityTracker contextTracker;

    public GenerateEngine(UnitProcessor unitProcessor, ModuleBuild moduleBuild,
                          StateStore stateStore, ExecutionConfig executionConfig) {
        // A tracker with an effectively unlimited budget: callers that do not care about
        // Spring cost control (most of this class's own tests) never trip it.
        this(unitProcessor, moduleBuild, stateStore, executionConfig,
                new ContextKeyStabilityTracker(Integer.MAX_VALUE));
    }

    public GenerateEngine(UnitProcessor unitProcessor, ModuleBuild moduleBuild, StateStore stateStore,
                          ExecutionConfig executionConfig, ContextKeyStabilityTracker contextTracker) {
        this.unitProcessor = Objects.requireNonNull(unitProcessor, "unitProcessor");
        this.moduleBuild = Objects.requireNonNull(moduleBuild, "moduleBuild");
        this.stateStore = Objects.requireNonNull(stateStore, "stateStore");
        this.executionConfig = Objects.requireNonNull(executionConfig, "executionConfig");
        this.contextTracker = Objects.requireNonNull(contextTracker, "contextTracker");
    }

    /** Convenience for a run with no {@code --tier}/{@code --no-spring} restriction. */
    public GenerateResult run(RunState initialState, Function<WorkUnit, UnitContext> contextLookup) {
        return run(initialState, contextLookup, TierRestriction.allTiers());
    }

    /**
     * @param initialState  the run state, with its units already discovered
     * @param contextLookup supplies the per-unit context for each unit the loop reaches;
     *                      a function rather than a prepared map so a unit's view of the
     *                      test class reflects what earlier units in the same class just
     *                      wrote to it
     * @param restriction   the invocation's {@code --tier}/{@code --no-spring} filter
     */
    public GenerateResult run(RunState initialState, Function<WorkUnit, UnitContext> contextLookup,
                              TierRestriction restriction) {
        List<String> preExistingFailures = failingTestNames(moduleBuild.runFullSuite());
        if (!preExistingFailures.isEmpty()) {
            // §2: the module must already build green. Generating against a red suite
            // would make every later "did this test pass?" answer meaningless.
            LOGGER.error("The module's test suite is already failing before this run started: {}",
                    preExistingFailures);
            return new GenerateResult(stateStore.save(initialState),
                    GenerateResult.ExitReason.PREFLIGHT_FAILED, preExistingFailures);
        }

        RunState state = stateStore.save(initialState);
        int consecutiveFailures = 0;
        int unitsProcessed = 0;
        boolean anythingKept = false;

        for (WorkUnit unit : unitsToProcess(state, restriction)) {
            if (reachedUnitLimit(unitsProcessed)) {
                LOGGER.info("Reached execution.maxUnitsPerRun ({}); stopping.", executionConfig.maxUnitsPerRun());
                break;
            }

            if (!state.springTiers().isAvailable(unit.tier())) {
                String reason = state.springTiers().unavailable()
                        .getOrDefault(unit.tier(), unit.tier() + " is not available on this module");
                state = stateStore.updateUnit(state, unit.withSkipped(UnitStatus.SKIPPED_TIER_UNAVAILABLE, reason));
                continue;
            }

            state = stateStore.markInProgress(state, unit.id());
            unitsProcessed++;

            long startedAt = System.currentTimeMillis();
            UnitOutcome outcome = processSafely(unit, contextLookup);
            long durationMillis = System.currentTimeMillis() - startedAt;
            state = stateStore.updateUnit(state, applyOutcome(state, unit.id(), outcome, durationMillis));

            if (outcome.succeeded()) {
                anythingKept = true;
                consecutiveFailures = 0;
            } else if (++consecutiveFailures >= executionConfig.consecutiveFailureAbort()) {
                LOGGER.error("Aborting: {} consecutive units produced nothing usable.", consecutiveFailures);
                return new GenerateResult(state,
                        GenerateResult.ExitReason.ABORTED_ON_CONSECUTIVE_FAILURES, List.of());
            }

            if (contextTracker.budgetExhausted()) {
                // §9.6: nearly always a context-key violation (§7.6) rather than genuine
                // need, so the classes that forked are named rather than merely counted.
                LOGGER.error("Aborting: Spring context-load budget ({}) exhausted; forked classes: {}",
                        contextTracker.budget(), contextTracker.forkedClasses());
                return new GenerateResult(state, GenerateResult.ExitReason.CONTEXT_LOAD_BUDGET_EXHAUSTED,
                        List.of(), List.copyOf(contextTracker.forkedClasses()));
            }
        }

        return finalise(state, anythingKept);
    }

    /**
     * Runs one unit and converts <em>any</em> escape into a terminal outcome.
     *
     * <p>An unexpected exception is recorded as {@code PROVIDER_ERROR} rather than a new
     * status of its own: like a transport failure, nothing was learned about the unit
     * itself, and it should be retried on the next run (§8.1's ALWAYS retry policy).
     */
    private UnitOutcome processSafely(WorkUnit unit, Function<WorkUnit, UnitContext> contextLookup) {
        try {
            return unitProcessor.process(contextLookup.apply(unit));
        } catch (RuntimeException e) {
            LOGGER.warn("Unit {} failed unexpectedly", unit.id(), e);
            return UnitOutcome.failed(UnitStatus.PROVIDER_ERROR,
                    e.getClass().getSimpleName() + ": " + e.getMessage(), List.of());
        }
    }

    private WorkUnit applyOutcome(RunState state, WorkUnitId id, UnitOutcome outcome, long durationMillis) {
        return WorkUnitOutcomes.apply(state.unit(id).orElseThrow(), outcome, durationMillis);
    }

    /**
     * §9.5: the module's whole suite must still be green. Every unit's tests passed in
     * isolation, so a red suite here means the new tests interact - shared state, ordering
     * - which is exactly the failure that scoped runs cannot see and that must be reported
     * loudly rather than left for the developer's next build to discover.
     */
    private GenerateResult finalise(RunState state, boolean anythingKept) {
        List<String> failures = failingTestNames(moduleBuild.runFullSuite());
        if (!failures.isEmpty()) {
            LOGGER.error("Every unit passed in isolation, but the module's full suite is now failing: {}",
                    failures);
            return new GenerateResult(state, GenerateResult.ExitReason.FULL_SUITE_RED, failures);
        }
        if (!anythingKept && allAttemptedUnitsFailedWithProviderError(state)) {
            LOGGER.error("Every attempted unit failed with a provider error; the AI CLI may be unreachable.");
            return new GenerateResult(state, GenerateResult.ExitReason.PROVIDER_UNREACHABLE, List.of());
        }
        if (!anythingKept && !state.units().isEmpty()) {
            return new GenerateResult(state, GenerateResult.ExitReason.NOTHING_KEPT, List.of());
        }
        return new GenerateResult(state, GenerateResult.ExitReason.COMPLETED, List.of());
    }

    /**
     * §14.1 exit code 4, distinguished from the more general "nothing kept" (exit 3):
     * every unit this run actually attempted - not skipped, not still pending - ended in
     * {@code PROVIDER_ERROR}, which means the failure is almost certainly the AI CLI being
     * unreachable rather than the model's output being unusable.
     */
    private boolean allAttemptedUnitsFailedWithProviderError(RunState state) {
        List<WorkUnit> attempted = state.units().stream()
                .filter(unit -> ATTEMPTED_STATUSES.contains(unit.status()))
                .toList();
        return !attempted.isEmpty()
                && attempted.stream().allMatch(unit -> unit.status() == UnitStatus.PROVIDER_ERROR);
    }

    /**
     * Units still worth attempting this invocation, tier-ordered and class-grouped
     * (§9.6), restricted by {@code --tier}/{@code --no-spring}.
     *
     * <p>A unit the restriction excludes is left untouched - still {@code PENDING} - so a
     * later invocation without the restriction picks it up normally. Tier-availability
     * (§7.3) is a different, permanent kind of exclusion and is handled inside the loop
     * itself, where it can be recorded as {@code SKIPPED_TIER_UNAVAILABLE}.
     */
    private List<WorkUnit> unitsToProcess(RunState state, TierRestriction restriction) {
        List<WorkUnit> pending = new ArrayList<>();
        for (WorkUnit unit : state.units()) {
            if ((unit.status() == UnitStatus.PENDING || unit.status() == UnitStatus.IN_PROGRESS)
                    && restriction.allows(unit.tier())) {
                pending.add(unit);
            }
        }
        return TierScheduler.order(pending);
    }

    private boolean reachedUnitLimit(int unitsProcessed) {
        return executionConfig.maxUnitsPerRun() > 0 && unitsProcessed >= executionConfig.maxUnitsPerRun();
    }

    private List<String> failingTestNames(TestRunOutcome outcome) {
        Set<String> names = new LinkedHashSet<>();
        for (SurefireTestResult failure : outcome.failures()) {
            names.add(failure.className() + "#" + failure.testName());
        }
        return List.copyOf(names);
    }
}
