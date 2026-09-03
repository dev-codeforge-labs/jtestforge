package com.devmanchego.jtestforge.report;

import com.devmanchego.jtestforge.model.Baseline;
import com.devmanchego.jtestforge.model.Phase;
import com.devmanchego.jtestforge.model.RunState;
import com.devmanchego.jtestforge.model.SpringTierState;
import com.devmanchego.jtestforge.model.Tier;
import com.devmanchego.jtestforge.model.UnitStatus;
import com.devmanchego.jtestforge.model.WorkUnit;
import com.devmanchego.jtestforge.model.WorkUnitId;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** §15's aggregation logic - pure sums and group-bys over a {@link RunState} fixture. */
class ReportDataBuilderTest {

    private static final Instant NOW = Instant.parse("2026-08-31T10:00:00Z");

    private final ReportDataBuilder builder = new ReportDataBuilder();

    @Test
    void gapsStillOpenIsBaselineMinusClosedFlooredAtZero() {
        RunState state = fixtureState();

        ReportData data = builder.build(state);

        assertThat(data.gapsClosedThisRun()).isEqualTo(3); // gap-1, gap-2, gap-3
        assertThat(data.gapsStillOpen()).isEqualTo(2); // baseline 5 - 3
    }

    @Test
    void closingMoreGapsThanTheBaselineRecordedNeverGoesNegative() {
        RunState state = stateWith(new Baseline(0.0, 0.0, null, 1),
                List.of(unit("classify", Tier.PLAIN_UNIT, UnitStatus.DONE,
                        List.of("t"), List.of(), List.of("gap-1", "gap-2"), List.of(), 0, 0, 100)));

        assertThat(builder.build(state).gapsStillOpen()).isZero();
    }

    @Test
    void coverageAndMutantDeltasSumAcrossEveryKeptUnit() {
        ReportData data = builder.build(fixtureState());

        assertThat(data.linesCoveredThisRun()).isEqualTo(7);
        assertThat(data.branchesCoveredThisRun()).isEqualTo(2);
        assertThat(data.mutantsKilledThisRun()).isEqualTo(1);
    }

    @Test
    void classSummariesGroupEveryUnitOfTheSameClassTogether() {
        RunState state = stateWith(Baseline.notMeasured(), List.of(
                unit("classify", Tier.PLAIN_UNIT, UnitStatus.DONE, List.of("classify_a"), List.of(), List.of(), List.of(), 3, 0, 100),
                unit("settle", Tier.PLAIN_UNIT, UnitStatus.DONE, List.of("settle_a"), List.of(), List.of(), List.of(), 2, 0, 100)));

        List<ReportData.ClassSummary> summaries = builder.build(state).classSummaries();

        assertThat(summaries).hasSize(1);
        assertThat(summaries.get(0).testsAdded()).containsExactlyInAnyOrder("classify_a", "settle_a");
    }

    @Test
    void tierSummariesAreOrderedCheapestFirstAndSkipTiersWithNoUnits() {
        RunState state = stateWith(Baseline.notMeasured(), List.of(
                unit("h", Tier.WEB_SLICE, UnitStatus.DONE, List.of("h1"), List.of(), List.of(), List.of(), 0, 0, 1500),
                unit("c", Tier.PLAIN_UNIT, UnitStatus.DONE, List.of("c1"), List.of(), List.of(), List.of(), 0, 0, 500)));

        List<ReportData.TierSummary> tiers = builder.build(state).tierSummaries();

        assertThat(tiers).extracting(ReportData.TierSummary::tier).containsExactly(Tier.PLAIN_UNIT, Tier.WEB_SLICE);
    }

    @Test
    void aTierUnitIsAttemptedUnlessItWasNeverStartedOrExcludedBySelectionOrAvailability() {
        RunState state = stateWith(Baseline.notMeasured(), List.of(
                unit("a", Tier.PLAIN_UNIT, UnitStatus.PENDING, List.of(), List.of(), List.of(), List.of(), 0, 0, 0),
                unit("b", Tier.PLAIN_UNIT, UnitStatus.SKIPPED_FILTERED, List.of(), List.of(), List.of(), List.of(), 0, 0, 0),
                unit("c", Tier.PLAIN_UNIT, UnitStatus.SKIPPED_TIER_UNAVAILABLE, List.of(), List.of(), List.of(), List.of(), 0, 0, 0),
                unit("d", Tier.PLAIN_UNIT, UnitStatus.FAILED_COMPILE, List.of(), List.of(), List.of(), List.of(), 0, 0, 200)));

        ReportData.TierSummary tier = builder.build(state).tierSummaries().get(0);

        assertThat(tier.attempted()).isEqualTo(1);
        assertThat(tier.discarded()).isEqualTo(1);
    }

    @Test
    void discardReasonsAreGroupedByGuardIdAndSortedMostFrequentFirst() {
        RunState state = stateWith(Baseline.notMeasured(), List.of(
                unit("a", Tier.PLAIN_UNIT, UnitStatus.FAILED_COMPILE,
                        List.of(), List.of("NO_ASSERTION rejected x: no assertion",
                                "NO_ASSERTION rejected y: no assertion", "merge refused: duplicate"),
                        List.of(), List.of(), 0, 0, 0)));

        List<ReportData.DiscardReasonCount> reasons = builder.build(state).discardReasons();

        assertThat(reasons).extracting(ReportData.DiscardReasonCount::category)
                .containsExactly("NO_ASSERTION", "merge refused");
        assertThat(reasons.get(0).count()).isEqualTo(2);
    }

    @Test
    void withNoUnitsAtAllTheOpenGapsCountStillReflectsTheBaseline() {
        // §15: "the open-gaps list survives a run where nothing was generated at all,
        // since it is the tool's most actionable output on its own."
        RunState state = stateWith(new Baseline(0.3, 0.2, null, 12), List.of());

        ReportData data = builder.build(state);

        assertThat(data.gapsStillOpen()).isEqualTo(12);
        assertThat(data.gapsClosedThisRun()).isZero();
        assertThat(data.classSummaries()).isEmpty();
        assertThat(data.tierSummaries()).isEmpty();
    }

    @Test
    void aNullBaselineIsTreatedAsNotMeasuredRatherThanThrowing() {
        RunState state = RunState.startNew("run-1", NOW, Phase.GENERATE, "C:/app", "sha256:cfg", "claude",
                SpringTierState.springDisabled(40), List.of());

        assertThat(builder.build(state).baseline()).isEqualTo(Baseline.notMeasured());
    }

    // --- fixtures -------------------------------------------------------------------

    private RunState fixtureState() {
        return stateWith(new Baseline(0.412, 0.301, null, 5), List.of(
                unit("classify", Tier.PLAIN_UNIT, UnitStatus.DONE,
                        List.of("classify_a", "classify_b"), List.of("NO_ASSERTION rejected foo: no assertion"),
                        List.of("gap-1"), List.of(), 7, 2, 500),
                unit("findById", Tier.WEB_SLICE, UnitStatus.DONE,
                        List.of(), List.of(), List.of("gap-2", "gap-3"), List.of(), 0, 0, 1500),
                unit("settle", Tier.PLAIN_UNIT, UnitStatus.FAILED_COMPILE,
                        List.of(), List.of("merge refused: duplicate"), List.of(), List.of(), 0, 0, 300),
                unit("post", Tier.PLAIN_UNIT, UnitStatus.SKIPPED_TIER_UNAVAILABLE,
                        List.of(), List.of(), List.of(), List.of(), 0, 0, 0),
                unit("add", Tier.PLAIN_UNIT, UnitStatus.DONE,
                        List.of("add_killsMutant"), List.of(), List.of(), List.of("m1"), 0, 0, 2000)));
    }

    private RunState stateWith(Baseline baseline, List<WorkUnit> units) {
        RunState state = RunState.startNew("run-1", NOW, Phase.GENERATE, "C:/app", "sha256:cfg", "claude",
                SpringTierState.springDisabled(40), units);
        return baseline == null ? state : state.withBaseline(baseline);
    }

    private WorkUnit unit(String method, Tier tier, UnitStatus status, List<String> addedTests,
                          List<String> discarded, List<String> gapsClosed, List<String> mutantsKilled,
                          int linesDelta, int branchesDelta, long durationMillis) {
        WorkUnitId id = WorkUnitId.of("com.acme.PaymentService", method + "()", tier);
        return new WorkUnit(id, "PaymentServiceTest.java", "PaymentService.java", "sha256:src", "sha256:test",
                status, 1, addedTests, List.of(), discarded, gapsClosed, mutantsKilled,
                linesDelta, branchesDelta, durationMillis, null, null);
    }
}
