package com.devmanchego.jtestforge.model;

/**
 * The measurements taken before any generation, against which every unit's value gate is
 * judged — jtestforge-specification.md §8, §9.2, §10.1.
 *
 * <p>{@code mutationScore} is {@code null} for a {@code generate} run: pass 1 never runs
 * PIT, and a zero would falsely read as "measured, and it was zero".
 *
 * @param lineCoverage              module line coverage, 0.0-1.0
 * @param branchCoverage            module branch coverage, 0.0-1.0
 * @param mutationScore             module mutation score 0.0-1.0, or {@code null} if not measured
 * @param openFrameworkSemanticGaps how many §7.5 gaps were unverified at baseline
 */
public record Baseline(
        double lineCoverage,
        double branchCoverage,
        Double mutationScore,
        int openFrameworkSemanticGaps) {

    public static Baseline notMeasured() {
        return new Baseline(0.0, 0.0, null, 0);
    }
}
