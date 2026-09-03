package com.devmanchego.jtestforge.model;

import java.util.List;

/**
 * Before/after comparison of one method's coverage, scoped to a single unit —
 * jtestforge-specification.md §9.4 step 9, the value gate: "if zero and
 * requireCoverageGain is true, revert and mark DISCARDED_NO_VALUE".
 *
 * @param linesCoveredDelta    newly-covered lines, {@code after - before} (never negative
 *                             in practice: a kept unit only ever adds tests)
 * @param branchesCoveredDelta newly-covered branches, likewise
 * @param newlyCoveredLines    the specific line numbers that went from missed to covered,
 *                             for the run report
 */
public record CoverageDelta(int linesCoveredDelta, int branchesCoveredDelta, List<Integer> newlyCoveredLines) {

    public CoverageDelta {
        newlyCoveredLines = newlyCoveredLines == null ? List.of() : List.copyOf(newlyCoveredLines);
    }

    /** Whether this unit moved the metric at all - the value gate's pass/fail. */
    public boolean hasImproved() {
        return linesCoveredDelta > 0 || branchesCoveredDelta > 0;
    }
}
