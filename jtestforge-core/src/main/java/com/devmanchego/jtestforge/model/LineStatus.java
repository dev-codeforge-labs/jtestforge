package com.devmanchego.jtestforge.model;

/**
 * JaCoCo's per-line instruction and branch counts, from one {@code <line>} element of a
 * {@code <sourcefile>} — jtestforge-specification.md §8's use of JaCoCo for the baseline
 * (§9.2) and per-unit value gate (§9.4).
 *
 * <p>A line JaCoCo never instrumented at all (blank lines, comments, lines outside any
 * method) simply has no entry - there is no "zero/zero" {@code LineStatus} for it, and
 * callers must treat an absent line number as "not executable source", not as "missed".
 *
 * @param missedInstructions instructions on this line that never executed
 * @param coveredInstructions instructions on this line that executed at least once
 * @param missedBranches     branches on this line that took only one outcome, or were
 *                           never taken at all
 * @param coveredBranches    branches on this line that were observed taking an outcome
 */
public record LineStatus(int missedInstructions, int coveredInstructions, int missedBranches, int coveredBranches) {

    /** Whether any instruction on this line executed at least once. */
    public boolean isCovered() {
        return coveredInstructions > 0 || coveredBranches > 0;
    }

    /** A line with both a taken and an untaken branch outcome - a partial branch. */
    public boolean isPartiallyCoveredBranch() {
        return missedBranches > 0 && coveredBranches > 0;
    }
}
