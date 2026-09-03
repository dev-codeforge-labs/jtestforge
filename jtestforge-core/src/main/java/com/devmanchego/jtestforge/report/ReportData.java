package com.devmanchego.jtestforge.report;

import com.devmanchego.jtestforge.model.Baseline;
import com.devmanchego.jtestforge.model.Phase;
import com.devmanchego.jtestforge.model.SpringTierState;
import com.devmanchego.jtestforge.model.Tier;
import com.devmanchego.jtestforge.model.UnitStatus;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Everything a run report renders, computed once from {@code state.json} and shared by
 * every output format — jtestforge-specification.md §15.
 *
 * <p>What this class deliberately does <b>not</b> attempt: a new "coverage after" or
 * "mutation score after" percentage. Computing either correctly needs the module's total
 * line/branch/mutant counts, which the state file does not carry - only the per-unit
 * deltas measured against the baseline. Reporting a fabricated percentage would violate
 * the "judgement-free rendering from state.json" this whole class exists to be; reporting
 * the real, directly-derivable deltas ({@link #linesCoveredThisRun}, etc.) instead is
 * accurate about what is actually known.
 *
 * @param baseline               measurements taken before this run, {@code null} if never measured
 * @param gapsClosedThisRun      sum of every kept unit's {@code semanticGapsClosed}
 * @param gapsStillOpen          {@code baseline.openFrameworkSemanticGaps() - gapsClosedThisRun},
 *                               floored at zero; the tool's most actionable number on its own (§15)
 * @param mutantsKilledThisRun   sum of every kept unit's {@code mutantsKilled}
 * @param linesCoveredThisRun    sum of every kept unit's {@code linesCoveredDelta}
 * @param branchesCoveredThisRun sum of every kept unit's {@code branchesCoveredDelta}
 * @param classSummaries         one entry per production class touched, in first-seen order
 * @param tierSummaries          one entry per tier that had at least one unit, cheapest first
 * @param discardReasons         candidate discard reasons, grouped by category and counted,
 *                               most frequent first - the discard-reason breakdown (§15)
 * @param statusCounts           unit count per {@link UnitStatus}, as {@link com.devmanchego.jtestforge.model.RunState#statusCounts()}
 */
public record ReportData(
        String runId,
        Phase phase,
        String modulePath,
        String providerId,
        Instant startedAt,
        Instant updatedAt,
        Baseline baseline,
        int gapsClosedThisRun,
        int gapsStillOpen,
        int mutantsKilledThisRun,
        int linesCoveredThisRun,
        int branchesCoveredThisRun,
        SpringTierState springTiers,
        List<ClassSummary> classSummaries,
        List<TierSummary> tierSummaries,
        List<DiscardReasonCount> discardReasons,
        Map<UnitStatus, Integer> statusCounts) {

    public ReportData {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(phase, "phase");
        baseline = baseline == null ? Baseline.notMeasured() : baseline;
        classSummaries = classSummaries == null ? List.of() : List.copyOf(classSummaries);
        tierSummaries = tierSummaries == null ? List.of() : List.copyOf(tierSummaries);
        discardReasons = discardReasons == null ? List.of() : List.copyOf(discardReasons);
        statusCounts = statusCounts == null ? Map.of() : Map.copyOf(statusCounts);
    }

    public int totalUnits() {
        return statusCounts.values().stream().mapToInt(Integer::intValue).sum();
    }

    public int unitsKept() {
        return statusCounts.getOrDefault(UnitStatus.DONE, 0);
    }

    /**
     * One production class this run touched: what was added to its tests, what was
     * thrown away and why, and (pass 2) which mutants died.
     *
     * @param className     fully-qualified production class
     * @param testsAdded    every kept test method name, across all of this class's units
     * @param discarded     every discarded candidate's reason, across all of this class's units
     * @param mutantsKilled mutant ids killed, across all of this class's units
     */
    public record ClassSummary(String className, List<String> testsAdded, List<String> discarded,
                               List<String> mutantsKilled) {
        public ClassSummary {
            testsAdded = testsAdded == null ? List.of() : List.copyOf(testsAdded);
            discarded = discarded == null ? List.of() : List.copyOf(discarded);
            mutantsKilled = mutantsKilled == null ? List.of() : List.copyOf(mutantsKilled);
        }
    }

    /**
     * §15: "units attempted, kept and discarded, and wall-clock spent" - what makes the
     * cost of Spring support visible instead of merely felt.
     */
    public record TierSummary(Tier tier, int attempted, int kept, int discarded, long wallClockMillis) {
    }

    public record DiscardReasonCount(String category, int count) {
    }
}
