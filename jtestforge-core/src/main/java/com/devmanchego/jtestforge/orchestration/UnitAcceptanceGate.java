package com.devmanchego.jtestforge.orchestration;

/**
 * Step 9 of the per-unit loop — jtestforge-specification.md §9.4 step 9 for pass 1,
 * §10.1 step 5 for pass 2. Everything before this point (generate, guard, merge, compile,
 * run) is identical between the two passes and lives in {@link DefaultUnitProcessor}; this
 * is the one seam where they differ, which is why it is injected rather than hard-coded.
 *
 * <p>Two implementations: {@code CoverageAndGapAcceptanceGate} measures a coverage delta
 * and checks which framework-semantic gaps the merged tests actually assert;
 * {@code MutationAcceptanceGate} re-runs PIT scoped to the unit's class and checks whether
 * a previously-surviving mutant is now killed.
 */
public interface UnitAcceptanceGate {

    AcceptanceVerdict evaluate(UnitContext context, MergedCandidates merged);
}
