package com.devmanchego.jtestforge.coverage;

import com.devmanchego.jtestforge.model.ClassCoverage;
import com.devmanchego.jtestforge.model.CoverageDelta;
import com.devmanchego.jtestforge.model.MethodCoverage;
import com.devmanchego.jtestforge.model.ProductionMethod;

import java.util.List;
import java.util.Optional;

/**
 * Computes one method's coverage delta between two JaCoCo snapshots —
 * jtestforge-specification.md §9.4 step 9, the value gate a unit must clear when
 * {@code requireCoverageGain} is true.
 *
 * <p>{@link CoverageDelta#linesCoveredDelta()} and {@link CoverageDelta#branchesCoveredDelta()}
 * come from JaCoCo's own per-method counters (authoritative - JaCoCo already scopes them
 * correctly to the method's bytecode), found via {@link CoverageMethodJoiner}.
 * {@link CoverageDelta#newlyCoveredLines()} comes separately, from the method's AST line
 * range intersected with the class's per-line data, since the counters alone cannot say
 * <em>which</em> lines newly became covered - only how many.
 *
 * <p>Both deltas are clamped at zero. A negative delta would mean coverage regressed
 * between two reports of what should be the same, growing test run - not a real signal
 * {@code requireCoverageGain} should ever act on.
 */
public final class CoverageDeltaCalculator {

    private final CoverageMethodJoiner joiner;

    public CoverageDeltaCalculator() {
        this(new CoverageMethodJoiner());
    }

    public CoverageDeltaCalculator(CoverageMethodJoiner joiner) {
        this.joiner = joiner;
    }

    public CoverageDelta delta(ProductionMethod method, ClassCoverage before, ClassCoverage after) {
        Optional<MethodCoverage> beforeMethod = joiner.find(method, before);
        Optional<MethodCoverage> afterMethod = joiner.find(method, after);

        int linesCoveredDelta = clampAtZero(
                afterMethod.map(MethodCoverage::linesCovered).orElse(0)
                        - beforeMethod.map(MethodCoverage::linesCovered).orElse(0));
        int branchesCoveredDelta = clampAtZero(
                afterMethod.map(MethodCoverage::branchesCovered).orElse(0)
                        - beforeMethod.map(MethodCoverage::branchesCovered).orElse(0));

        List<Integer> newlyCoveredLines = newlyCoveredLines(method, before, after);
        return new CoverageDelta(linesCoveredDelta, branchesCoveredDelta, newlyCoveredLines);
    }

    private List<Integer> newlyCoveredLines(ProductionMethod method, ClassCoverage before, ClassCoverage after) {
        var uncoveredBefore = before.uncoveredLineNumbers(method.startLine(), method.endLine());
        var coveredAfter = after.coveredLineNumbers();
        return uncoveredBefore.stream().filter(coveredAfter::contains).sorted().toList();
    }

    private int clampAtZero(int value) {
        return Math.max(0, value);
    }
}
