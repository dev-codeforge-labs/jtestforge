package com.devmanchego.jtestforge.orchestration;

import com.devmanchego.jtestforge.build.ModuleBuild;
import com.devmanchego.jtestforge.build.TestRunOutcome;
import com.devmanchego.jtestforge.config.HardenConfig;
import com.devmanchego.jtestforge.model.Mutant;
import com.devmanchego.jtestforge.model.MutationReport;
import com.devmanchego.jtestforge.model.RunState;
import com.devmanchego.jtestforge.model.SurefireTestResult;
import com.devmanchego.jtestforge.model.UnitStatus;
import com.devmanchego.jtestforge.model.WorkUnit;
import com.devmanchego.jtestforge.model.WorkUnitId;
import com.devmanchego.jtestforge.mutation.MutantBehaviourTranslator;
import com.devmanchego.jtestforge.mutation.MutationException;
import com.devmanchego.jtestforge.mutation.MutationRequest;
import com.devmanchego.jtestforge.mutation.MutationRunner;
import com.devmanchego.jtestforge.mutation.UnkillableMutantStore;
import com.devmanchego.jtestforge.state.StateStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * Pass 2 — jtestforge-specification.md §10.1.
 *
 * <p>The same per-unit loop as pass 1 (steps 3-8, {@link DefaultUnitProcessor}), with a
 * {@link MutationAcceptanceGate} standing in for step 9: instead of a coverage delta or a
 * closed framework-semantic gap, the acceptance question is whether a scoped PIT re-run
 * kills a mutant that survived the baseline.
 *
 * <p>What this class adds on top of that shared loop, all specific to mutation testing:
 *
 * <ul>
 *   <li><b>Tier restriction (§10.3).</b> The baseline PIT run's {@code targetClasses}/
 *       {@code targetTests} are built only from classes at or below
 *       {@code harden.maxTierForMutation} - arithmetic, not conservatism: PIT re-runs the
 *       covering tests once per mutant, and a cached Spring context is still orders of
 *       magnitude slower per execution than a plain unit test.</li>
 *   <li><b>A per-unit retry budget.</b> Unlike pass 1, where a unit is attempted once per
 *       invocation and retried only on a later {@code --resume}, a mutant group here is
 *       retried up to {@code maxAttemptsPerMutant} times <em>within this same run</em>,
 *       because giving up on the first miss would treat every hard-to-kill-but-genuinely-
 *       killable mutant the same as a truly equivalent one.</li>
 *   <li><b>Promotion to the unkillable list.</b> A group that exhausts its retry budget
 *       without a single kill has every one of its mutants appended to
 *       {@code unkillableMutantsFile} and is never attempted again - equivalent mutants
 *       exist and are undecidable in general, and without this the tool would burn tokens
 *       on the same ones forever.</li>
 * </ul>
 */
public final class HardenEngine {

    private static final Logger LOGGER = LoggerFactory.getLogger(HardenEngine.class);

    /** Statuses meaning a unit was genuinely attempted this run, not skipped or pending. */
    private static final Set<UnitStatus> ATTEMPTED_STATUSES = Set.of(UnitStatus.DONE,
            UnitStatus.FAILED_COMPILE, UnitStatus.FAILED_ASSERTION, UnitStatus.DISCARDED_NO_VALUE,
            UnitStatus.SKIPPED_UNKILLABLE, UnitStatus.PROVIDER_ERROR);

    private final UnitProcessor unitProcessor;
    private final ModuleBuild moduleBuild;
    private final StateStore stateStore;
    private final MutationRunner mutationRunner;
    private final UnkillableMutantStore unkillableStore;
    private final HardenConfig hardenConfig;
    private final Path modulePath;
    private final Path historyFile;
    private final Duration mutationTimeout;
    private final MutantBehaviourTranslator translator = new MutantBehaviourTranslator();

    public HardenEngine(UnitProcessor unitProcessor, ModuleBuild moduleBuild, StateStore stateStore,
                        MutationRunner mutationRunner, UnkillableMutantStore unkillableStore,
                        HardenConfig hardenConfig, Path modulePath, Path historyFile, Duration mutationTimeout) {
        this.unitProcessor = Objects.requireNonNull(unitProcessor, "unitProcessor");
        this.moduleBuild = Objects.requireNonNull(moduleBuild, "moduleBuild");
        this.stateStore = Objects.requireNonNull(stateStore, "stateStore");
        this.mutationRunner = Objects.requireNonNull(mutationRunner, "mutationRunner");
        this.unkillableStore = Objects.requireNonNull(unkillableStore, "unkillableStore");
        this.hardenConfig = Objects.requireNonNull(hardenConfig, "hardenConfig");
        this.modulePath = Objects.requireNonNull(modulePath, "modulePath");
        this.historyFile = historyFile;
        this.mutationTimeout = Objects.requireNonNull(mutationTimeout, "mutationTimeout");
    }

    /**
     * @param initialState    the run state (units are discovered here, not beforehand -
     *                        pass 2 has no equivalent of pass 1's pre-populated state)
     * @param eligibleClasses the module's full production/test class inventory, each
     *                        tagged with the tier it was generated at (§10.3's restriction
     *                        is applied to this list, not computed by this class)
     * @param contextLookup   builds a complete {@link UnitContext} - including its own
     *                        {@link WorkUnit}, at the correct tier - from one mutant group
     */
    public HardenResult run(RunState initialState, List<TierScopedTestClass> eligibleClasses,
                            Function<List<Mutant>, UnitContext> contextLookup) {
        List<String> preExistingFailures = failingTestNames(moduleBuild.runFullSuite());
        if (!preExistingFailures.isEmpty()) {
            // §10.1 step 1: hardening a red suite is meaningless.
            LOGGER.error("The module's test suite is already failing; harden requires a green suite: {}",
                    preExistingFailures);
            return new HardenResult(stateStore.save(initialState), HardenResult.ExitReason.PREFLIGHT_FAILED,
                    preExistingFailures);
        }

        List<String> targetClasses = restrictedTargetClasses(eligibleClasses);
        List<String> targetTests = restrictedTargetTests(eligibleClasses);
        RunState state = stateStore.save(initialState);
        if (targetClasses.isEmpty()) {
            LOGGER.info("No class at or below harden.maxTierForMutation ({}); nothing to do.",
                    hardenConfig.maxTierForMutation());
            return new HardenResult(state, HardenResult.ExitReason.COMPLETED, List.of());
        }

        MutationReport baseline;
        try {
            baseline = mutationRunner.run(new MutationRequest(modulePath, targetClasses, targetTests,
                    historyFile, hardenConfig.mutators(), mutationTimeout));
        } catch (MutationException e) {
            LOGGER.error("Baseline PIT run failed", e);
            return new HardenResult(state, HardenResult.ExitReason.PREFLIGHT_FAILED,
                    List.of("baseline PIT run failed: " + e.getMessage()));
        }

        Set<String> unkillableIds = unkillableStore.loadIds();
        List<Mutant> eligibleMutants = baseline.survivedOrUncovered().stream()
                .filter(mutant -> !unkillableIds.contains(mutant.stableId()))
                .toList();
        List<List<Mutant>> groups = translator.group(eligibleMutants, hardenConfig.maxMutantsPerPrompt());

        boolean anythingKept = false;
        for (List<Mutant> group : groups) {
            UnitOutcomeSummary summary = processGroup(state, group, contextLookup);
            state = summary.state();
            anythingKept |= summary.killed();
        }

        return finalise(state, anythingKept, baseline);
    }

    /**
     * Runs one mutant group through the shared loop, retrying up to
     * {@code maxAttemptsPerMutant} times, then promotes every mutant in the group to the
     * unkillable list if none of the attempts killed one.
     *
     * <p>{@code contextLookup} is called once, up front; each retry reuses that same
     * {@link UnitContext} (with only the unit's {@code attempts} swapped in), rather than
     * re-fetching it. A failed attempt always reverts its own file changes first, so the
     * test class is back to its pre-attempt shape by the time the next retry starts - the
     * one exception being a Spring-tier escalation's mock-bean synthesis (§7.6), which is
     * deliberately permanent. That narrow gap only matters when
     * {@code harden.maxTierForMutation} is raised above the {@code PLAIN_UNIT} default
     * (§10.3), where it does not apply at all.
     */
    private UnitOutcomeSummary processGroup(
            RunState state, List<Mutant> group, Function<List<Mutant>, UnitContext> contextLookup) {
        UnitContext initialContext = contextLookup.apply(group);
        WorkUnitId id = initialContext.unit().id();

        state = ensureUnitPresent(state, initialContext.unit());
        int startingAttempts = state.unit(id).orElseThrow().attempts();

        boolean killed = false;
        UnitOutcome lastOutcome = null;
        for (int attempt = startingAttempts; attempt < hardenConfig.maxAttemptsPerMutant(); attempt++) {
            // Not stateStore.markInProgress: its own +1 would immediately be overwritten
            // by the explicit attempt count below, which is the one this loop actually
            // needs persisted - it is what keeps two retries' transcripts from colliding
            // (DefaultUnitProcessor.transcriptEpoch).
            WorkUnit attemptUnit = state.unit(id).orElseThrow()
                    .withStatus(UnitStatus.IN_PROGRESS).withAttempts(attempt);
            state = stateStore.updateUnit(state, attemptUnit);
            UnitContext context = initialContext.withUnit(attemptUnit);

            long startedAt = System.currentTimeMillis();
            lastOutcome = processSafely(context);
            long durationMillis = System.currentTimeMillis() - startedAt;
            state = stateStore.updateUnit(state, WorkUnitOutcomes.apply(attemptUnit, lastOutcome, durationMillis));

            if (lastOutcome.succeeded()) {
                killed = true;
                break;
            }
        }

        // A run that exhausted its attempts on transport failures learned nothing about
        // whether the mutant is killable - the AI CLI was simply unreachable. Promoting it
        // to unkillable.txt would blacklist it permanently, even once the CLI comes back;
        // leaving the unit at PROVIDER_ERROR instead retries it next run (§8.1: ALWAYS).
        boolean exhaustedOnTransportFailure = lastOutcome != null && lastOutcome.status() == UnitStatus.PROVIDER_ERROR;
        if (!killed && !exhaustedOnTransportFailure) {
            state = promoteToUnkillable(state, id, group, lastOutcome);
        }
        return new UnitOutcomeSummary(state, killed);
    }

    private RunState ensureUnitPresent(RunState state, WorkUnit unit) {
        if (state.unit(unit.id()).isPresent()) {
            return state;
        }
        List<WorkUnit> units = new ArrayList<>(state.units());
        units.add(unit);
        return state.withUnits(units);
    }

    /**
     * Every mutant this group targeted is recorded, not only the ones the last attempt
     * happened to still be verifying - a mutant reaches this point exactly when
     * {@code maxAttemptsPerMutant} full unit attempts (each with its own repair sub-loop)
     * produced no kill for the group at all (§10.1 step 6).
     */
    private RunState promoteToUnkillable(RunState state, WorkUnitId id, List<Mutant> group, UnitOutcome lastOutcome) {
        String reason = "maxAttemptsPerMutant (" + hardenConfig.maxAttemptsPerMutant() + ") exhausted"
                + (lastOutcome == null || lastOutcome.error() == null ? "" : ": " + lastOutcome.error());
        for (Mutant mutant : group) {
            unkillableStore.append(mutant.stableId(), reason);
        }
        WorkUnit unit = state.unit(id).orElseThrow()
                .withSkipped(UnitStatus.SKIPPED_UNKILLABLE,
                        "none of this group's " + group.size() + " mutant(s) could be killed within "
                                + hardenConfig.maxAttemptsPerMutant() + " attempt(s)");
        return stateStore.updateUnit(state, unit);
    }

    /** Mirrors {@code GenerateEngine}'s own unexpected-exception handling. */
    private UnitOutcome processSafely(UnitContext context) {
        try {
            return unitProcessor.process(context);
        } catch (RuntimeException e) {
            LOGGER.warn("Unit {} failed unexpectedly", context.unit().id(), e);
            return UnitOutcome.failed(UnitStatus.PROVIDER_ERROR,
                    e.getClass().getSimpleName() + ": " + e.getMessage(), List.of());
        }
    }

    /**
     * §10.3: the baseline's {@code targetClasses}, restricted to classes at or below
     * {@code harden.maxTierForMutation}. {@code CONTEXT_SLICE} needs no special-casing
     * here - {@code ConfigValidator} already refuses a config that sets it as the ceiling.
     */
    List<String> restrictedTargetClasses(List<TierScopedTestClass> eligibleClasses) {
        return distinctSorted(eligibleClasses, TierScopedTestClass::productionClassFqn);
    }

    /** The same restriction, over {@code targetTests}. */
    List<String> restrictedTargetTests(List<TierScopedTestClass> eligibleClasses) {
        return distinctSorted(eligibleClasses, TierScopedTestClass::testClassFqn);
    }

    private List<String> distinctSorted(
            List<TierScopedTestClass> eligibleClasses, Function<TierScopedTestClass, String> extractor) {
        int maxOrdinal = hardenConfig.maxTierForMutation().ordinal();
        Set<String> names = new LinkedHashSet<>();
        for (TierScopedTestClass candidate : eligibleClasses) {
            if (candidate.tier().ordinal() <= maxOrdinal) {
                names.add(extractor.apply(candidate));
            }
        }
        return names.stream().sorted().toList();
    }

    /**
     * §10.1 step 5 mirrors §9.5: the module's whole suite must still be green after every
     * scoped verification passed.
     */
    private HardenResult finalise(RunState state, boolean anythingKept, MutationReport baseline) {
        List<String> failures = failingTestNames(moduleBuild.runFullSuite());
        if (!failures.isEmpty()) {
            LOGGER.error("Every unit passed in isolation, but the module's full suite is now failing: {}",
                    failures);
            return new HardenResult(state, HardenResult.ExitReason.FULL_SUITE_RED, failures, null);
        }

        Double finalScore = finalMutationScore(state, baseline);

        if (!anythingKept && allAttemptedUnitsFailedWithProviderError(state)) {
            LOGGER.error("Every attempted unit failed with a provider error; the AI CLI may be unreachable.");
            return new HardenResult(state, HardenResult.ExitReason.PROVIDER_UNREACHABLE, List.of(), finalScore);
        }
        if (!anythingKept && !state.units().isEmpty()) {
            return new HardenResult(state, HardenResult.ExitReason.NOTHING_KEPT, List.of(), finalScore);
        }
        if (finalScore != null && hardenConfig.minMutationScore() != null
                && finalScore * 100 < hardenConfig.minMutationScore()) {
            LOGGER.error("Final mutation score {} is below harden.minMutationScore ({})",
                    finalScore, hardenConfig.minMutationScore());
            return new HardenResult(state, HardenResult.ExitReason.MUTATION_SCORE_BELOW_THRESHOLD,
                    List.of(), finalScore);
        }
        return new HardenResult(state, HardenResult.ExitReason.COMPLETED, List.of(), finalScore);
    }

    /**
     * Killed mutants (baseline + this run) over the baseline's total mutant count -
     * derivable exactly, unlike a coverage-after percentage (§15), because the baseline
     * fixes the denominator and this run's own {@code mutantsKilled} record every
     * numerator contribution.
     */
    private Double finalMutationScore(RunState state, MutationReport baseline) {
        if (baseline == null || baseline.mutants().isEmpty()) {
            return null;
        }
        long total = baseline.mutants().size();
        long baselineKilled = baseline.mutants().stream()
                .filter(mutant -> mutant.status() == com.devmanchego.jtestforge.model.MutationStatus.KILLED)
                .count();
        long killedThisRun = state.units().stream().mapToLong(unit -> unit.mutantsKilled().size()).sum();
        return (baselineKilled + killedThisRun) / (double) total;
    }

    /** Mirrors {@code GenerateEngine}'s exit-4 detection: every attempted unit was a provider error. */
    private boolean allAttemptedUnitsFailedWithProviderError(RunState state) {
        List<WorkUnit> attempted = state.units().stream()
                .filter(unit -> ATTEMPTED_STATUSES.contains(unit.status()))
                .toList();
        return !attempted.isEmpty()
                && attempted.stream().allMatch(unit -> unit.status() == UnitStatus.PROVIDER_ERROR);
    }

    private List<String> failingTestNames(TestRunOutcome outcome) {
        Set<String> names = new LinkedHashSet<>();
        for (SurefireTestResult failure : outcome.failures()) {
            names.add(failure.className() + "#" + failure.testName());
        }
        return List.copyOf(names);
    }

    private record UnitOutcomeSummary(RunState state, boolean killed) {
    }
}
