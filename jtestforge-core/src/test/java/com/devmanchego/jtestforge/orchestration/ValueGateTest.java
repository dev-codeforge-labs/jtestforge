package com.devmanchego.jtestforge.orchestration;

import com.devmanchego.jtestforge.config.GenerateConfig;
import com.devmanchego.jtestforge.model.CoverageDelta;
import com.devmanchego.jtestforge.model.SemanticGap;
import com.devmanchego.jtestforge.model.SemanticGapKind;
import com.devmanchego.jtestforge.model.Tier;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * §9.4 step 9's value gate. The asymmetry between the plain tier and the Spring tiers is
 * the single most load-bearing decision in the whole tool, and the easiest to "simplify"
 * away by unifying the two branches - so it is pinned here from both sides.
 */
class ValueGateTest {

    private final ValueGate gate = new ValueGate(new GenerateConfig(null, null, null, null, null));

    @Test
    void aPlainUnitTestThatCoversNewLinesIsKept() {
        ValueVerdict verdict = gate.evaluate(Tier.PLAIN_UNIT, delta(3, 1), List.of());

        assertThat(verdict.keep()).isTrue();
        assertThat(verdict.reason()).contains("3 new line");
    }

    @Test
    void aPlainUnitTestThatCoversNothingNewIsDiscarded() {
        ValueVerdict verdict = gate.evaluate(Tier.PLAIN_UNIT, delta(0, 0), List.of());

        assertThat(verdict.keep()).isFalse();
        assertThat(verdict.reason()).contains("no line or branch");
    }

    @Test
    void aSpringUnitWithZeroCoverageDeltaButAClosedGapIsKept() {
        // THE regression test for gate 2 (§1). A @WebMvcTest verifying a request mapping
        // moves line coverage by exactly zero - the behaviour it checks lives in the
        // framework, not in the class's lines. Judging it on coverage would discard
        // precisely the tests Spring support exists to produce.
        ValueVerdict verdict = gate.evaluate(Tier.WEB_SLICE, delta(0, 0), List.of(mappingGap()));

        assertThat(verdict.keep()).isTrue();
        assertThat(verdict.gapsClosed()).hasSize(1);
        assertThat(verdict.reason()).contains("framework-semantic gap");
    }

    @Test
    void aSpringUnitWithNeitherAClosedGapNorCoverageIsDiscarded() {
        ValueVerdict verdict = gate.evaluate(Tier.WEB_SLICE, delta(0, 0), List.of());

        assertThat(verdict.keep()).isFalse();
        assertThat(verdict.reason()).contains("neither close a framework-semantic gap");
    }

    @Test
    void aSpringUnitThatOnlyMovesCoverageIsStillKept() {
        // The gap route is additional, not a replacement: a slice test that does happen to
        // cover new lines earns its place the ordinary way.
        assertThat(gate.evaluate(Tier.WEB_SLICE, delta(2, 0), List.of()).keep()).isTrue();
    }

    @Test
    void aClosedGapDoesNotRescueAPlainUnitTest() {
        // A PLAIN_UNIT unit is never generated for a gap; if one somehow carried gaps, the
        // coverage rule still governs, because a direct method call cannot exercise
        // framework behaviour however the test is phrased.
        assertThat(gate.evaluate(Tier.PLAIN_UNIT, delta(0, 0), List.of(mappingGap())).keep()).isFalse();
    }

    @Test
    void aBranchOnlyGainCountsAsAnImprovement() {
        assertThat(gate.evaluate(Tier.PLAIN_UNIT, delta(0, 2), List.of()).keep()).isTrue();
    }

    @Test
    void withRequireCoverageGainOffEverythingThatCompilesAndPassesIsKept() {
        ValueGate permissive = new ValueGate(new GenerateConfig(null, null, false, null, null));

        ValueVerdict verdict = permissive.evaluate(Tier.PLAIN_UNIT, delta(0, 0), List.of());

        assertThat(verdict.keep()).isTrue();
        assertThat(verdict.reason()).contains("requireCoverageGain is off");
    }

    @Test
    void anUnmeasurableCoverageDeltaIsTreatedAsNoImprovementNotAsSuccess() {
        // A null delta means JaCoCo produced nothing for the class. Treating "unknown" as
        // "improved" would keep tests on no evidence at all.
        assertThat(gate.evaluate(Tier.PLAIN_UNIT, null, List.of()).keep()).isFalse();
    }

    private CoverageDelta delta(int lines, int branches) {
        return new CoverageDelta(lines, branches, List.of());
    }

    private SemanticGap mappingGap() {
        return new SemanticGap(SemanticGapKind.REQUEST_MAPPING, "com.acme.web.OrderController",
                "findById", "The mapping is not verified.", "GET", "/api/orders/{id}", 10);
    }
}
