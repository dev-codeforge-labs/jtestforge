package com.devmanchego.jtestforge.report;

import com.devmanchego.jtestforge.model.RunState;
import com.devmanchego.jtestforge.model.Tier;
import com.devmanchego.jtestforge.model.UnitStatus;
import com.devmanchego.jtestforge.model.WorkUnit;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Computes {@link ReportData} from a {@link RunState} — jtestforge-specification.md §15.
 *
 * <p>Pure aggregation, nothing else: every number here is a sum, a group-by, or a
 * subtraction over what {@code state.json} already recorded. "Judgement-free rendering
 * from {@code state.json}" (the implementation plan's own phrase for this phase) is the
 * whole design brief - this class draws no conclusions, flags nothing as good or bad, and
 * never re-reads the target module's source to check anything the state file itself did
 * not already capture.
 */
public final class ReportDataBuilder {

    /** Statuses that count as "discarded" for the per-tier table - an attempt that produced nothing. */
    private static final Set<UnitStatus> DISCARDED_STATUSES = Set.of(
            UnitStatus.FAILED_COMPILE, UnitStatus.FAILED_ASSERTION, UnitStatus.DISCARDED_NO_VALUE,
            UnitStatus.SKIPPED_UNKILLABLE, UnitStatus.PROVIDER_ERROR);

    /** Statuses that mean a unit was never actually attempted - excluded from "attempted". */
    private static final Set<UnitStatus> NOT_ATTEMPTED_STATUSES = Set.of(
            UnitStatus.PENDING, UnitStatus.SKIPPED_FILTERED, UnitStatus.SKIPPED_TIER_UNAVAILABLE);

    public ReportData build(RunState state) {
        List<WorkUnit> units = state.units();

        int gapsClosed = units.stream().mapToInt(unit -> unit.semanticGapsClosed().size()).sum();
        int baselineOpenGaps = state.baseline() == null ? 0 : state.baseline().openFrameworkSemanticGaps();

        return new ReportData(
                state.runId(), state.phase(), state.modulePath(), state.providerId(),
                state.startedAt(), state.updatedAt(), state.baseline(),
                gapsClosed, Math.max(0, baselineOpenGaps - gapsClosed),
                units.stream().mapToInt(unit -> unit.mutantsKilled().size()).sum(),
                units.stream().mapToInt(WorkUnit::linesCoveredDelta).sum(),
                units.stream().mapToInt(WorkUnit::branchesCoveredDelta).sum(),
                state.springTiers(),
                classSummaries(units), tierSummaries(units), discardReasonBreakdown(units),
                state.statusCounts());
    }

    private List<ReportData.ClassSummary> classSummaries(List<WorkUnit> units) {
        Map<String, List<WorkUnit>> byClass = new LinkedHashMap<>();
        for (WorkUnit unit : units) {
            byClass.computeIfAbsent(unit.className(), key -> new ArrayList<>()).add(unit);
        }
        List<ReportData.ClassSummary> summaries = new ArrayList<>();
        for (Map.Entry<String, List<WorkUnit>> entry : byClass.entrySet()) {
            List<String> testsAdded = new ArrayList<>();
            List<String> discarded = new ArrayList<>();
            List<String> mutantsKilled = new ArrayList<>();
            for (WorkUnit unit : entry.getValue()) {
                testsAdded.addAll(unit.addedTests());
                discarded.addAll(unit.discardedTests());
                mutantsKilled.addAll(unit.mutantsKilled());
            }
            summaries.add(new ReportData.ClassSummary(entry.getKey(), testsAdded, discarded, mutantsKilled));
        }
        return summaries;
    }

    private List<ReportData.TierSummary> tierSummaries(List<WorkUnit> units) {
        Map<Tier, List<WorkUnit>> byTier = new LinkedHashMap<>();
        for (WorkUnit unit : units) {
            byTier.computeIfAbsent(unit.tier(), key -> new ArrayList<>()).add(unit);
        }
        List<ReportData.TierSummary> summaries = new ArrayList<>();
        for (Tier tier : Tier.values()) {
            List<WorkUnit> tierUnits = byTier.get(tier);
            if (tierUnits == null || tierUnits.isEmpty()) {
                continue;
            }
            int attempted = 0;
            int kept = 0;
            int discarded = 0;
            long wallClockMillis = 0;
            for (WorkUnit unit : tierUnits) {
                if (!NOT_ATTEMPTED_STATUSES.contains(unit.status())) {
                    attempted++;
                }
                if (unit.status() == UnitStatus.DONE) {
                    kept++;
                } else if (DISCARDED_STATUSES.contains(unit.status())) {
                    discarded++;
                }
                wallClockMillis += unit.durationMillis();
            }
            summaries.add(new ReportData.TierSummary(tier, attempted, kept, discarded, wallClockMillis));
        }
        return summaries;
    }

    /**
     * Groups every discarded candidate's reason by category - the guard id for a guard
     * rejection ({@code GuardRejection.toString()}'s {@code "<GUARD_ID> rejected ..."}
     * shape), or the phrase before the first colon for anything else (a response contract
     * violation, a merge refusal). Counted most-frequent first, so the report leads with
     * whichever failure mode is actually costing the most generations.
     */
    private List<ReportData.DiscardReasonCount> discardReasonBreakdown(List<WorkUnit> units) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (WorkUnit unit : units) {
            for (String reason : unit.discardedTests()) {
                counts.merge(categoryOf(reason), 1, Integer::sum);
            }
        }
        return counts.entrySet().stream()
                .map(entry -> new ReportData.DiscardReasonCount(entry.getKey(), entry.getValue()))
                .sorted((a, b) -> Integer.compare(b.count(), a.count()))
                .toList();
    }

    private String categoryOf(String discardReason) {
        int rejectedIndex = discardReason.indexOf(" rejected ");
        if (rejectedIndex > 0) {
            return discardReason.substring(0, rejectedIndex);
        }
        int colonIndex = discardReason.indexOf(':');
        if (colonIndex > 0) {
            return discardReason.substring(0, colonIndex).trim();
        }
        return "other";
    }
}
