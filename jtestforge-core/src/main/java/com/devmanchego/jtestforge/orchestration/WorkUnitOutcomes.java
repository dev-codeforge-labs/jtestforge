package com.devmanchego.jtestforge.orchestration;

import com.devmanchego.jtestforge.model.WorkUnit;

/**
 * Turns one {@link UnitOutcome} into the persisted {@link WorkUnit} state - shared by
 * {@link GenerateEngine} and {@link HardenEngine} so the two passes cannot silently drift
 * on how an outcome maps onto the state file (§8), which is also the sole source
 * {@code ReportRenderer} reads from (§15).
 *
 * <p>Lives here rather than as a {@code WorkUnit} method: {@code model} sits below
 * {@code orchestration} in this project's layering, and {@link UnitOutcome} belongs to
 * {@code orchestration} - giving {@code WorkUnit} a method that takes one would invert
 * that dependency.
 */
final class WorkUnitOutcomes {

    private WorkUnitOutcomes() {
    }

    static WorkUnit apply(WorkUnit unit, UnitOutcome outcome, long durationMillis) {
        return new WorkUnit(unit.id(), unit.testFile(), unit.sourceFile(), unit.sourceHash(), unit.testFileHash(),
                outcome.status(), unit.attempts(), outcome.addedTests(), outcome.addedImports(),
                outcome.discarded(), outcome.gapsClosed(), outcome.mutantsKilled(),
                outcome.linesCoveredDelta(), outcome.branchesCoveredDelta(),
                durationMillis, outcome.error(), unit.skipReason());
    }
}
