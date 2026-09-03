package com.devmanchego.jtestforge.orchestration;

import com.devmanchego.jtestforge.config.GenerateConfig;
import com.devmanchego.jtestforge.model.CoverageDelta;
import com.devmanchego.jtestforge.model.SemanticGap;
import com.devmanchego.jtestforge.model.Tier;

import java.util.List;
import java.util.Objects;

/**
 * Decides whether a unit's tests are kept — jtestforge-specification.md §9.4 step 9.
 *
 * <p><b>Evaluated per tier, and that is the whole point.</b> A plain unit test earns its
 * place by covering lines nothing covered before. A Spring-tier unit frequently cannot:
 * the behaviour it verifies - a request mapping, a validation rule, an authorisation
 * decision - lives in the framework, not in the class's own lines, so a perfectly good
 * slice test can move line coverage by exactly zero. Judging it on coverage alone would
 * discard precisely the tests gate 2 (§1) exists to produce.
 *
 * <p>This asymmetry is easy to "simplify" away by someone later unifying the two branches,
 * which is why {@code GenerateEngineTest} pins it with a dedicated regression test.
 */
public final class ValueGate {

    private final GenerateConfig config;

    public ValueGate(GenerateConfig config) {
        this.config = Objects.requireNonNull(config, "config");
    }

    /**
     * @param gapsAssertedByTheNewTests the unit's gaps whose required assertion shape the
     *                                  newly-kept tests actually demonstrate - measured
     *                                  after they passed, not assumed from the prompt
     */
    public ValueVerdict evaluate(
            Tier tier, CoverageDelta coverageDelta, List<SemanticGap> gapsAssertedByTheNewTests) {

        boolean coverageImproved = coverageDelta != null && coverageDelta.hasImproved();
        List<String> closedGapIds = gapsAssertedByTheNewTests.stream().map(SemanticGap::id).toList();

        if (tier != Tier.PLAIN_UNIT && !closedGapIds.isEmpty()) {
            return ValueVerdict.keep(
                    "closes " + closedGapIds.size() + " framework-semantic gap(s) that nothing verified",
                    closedGapIds);
        }
        if (coverageImproved) {
            return ValueVerdict.keep(
                    "covers " + coverageDelta.linesCoveredDelta() + " new line(s) and "
                            + coverageDelta.branchesCoveredDelta() + " new branch(es)",
                    closedGapIds);
        }
        if (!config.requireCoverageGain()) {
            return ValueVerdict.keep("kept without a coverage gain (generate.requireCoverageGain is off)",
                    closedGapIds);
        }
        return ValueVerdict.discard(tier == Tier.PLAIN_UNIT
                ? "the tests compile and pass but cover no line or branch that was not already covered"
                : "the tests compile and pass but neither close a framework-semantic gap nor cover "
                        + "anything new");
    }
}
