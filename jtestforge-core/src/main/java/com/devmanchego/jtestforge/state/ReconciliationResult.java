package com.devmanchego.jtestforge.state;

import com.devmanchego.jtestforge.model.RunState;
import com.devmanchego.jtestforge.model.WorkUnitId;

import java.util.List;

/**
 * Outcome of reconciling a loaded run state against the filesystem —
 * jtestforge-specification.md §8.2.3.
 *
 * @param state              the reconciled state, safe to resume from
 * @param resets             every unit reset to PENDING, with the reason, for the run log
 * @param unitsNeedingRevert units that were IN_PROGRESS when the previous run died and
 *                           whose partial file edits must be undone before work resumes
 * @param baselineInvalidated whether the config changed, discarding the stored baseline
 */
public record ReconciliationResult(
        RunState state,
        List<Reset> resets,
        List<WorkUnitId> unitsNeedingRevert,
        boolean baselineInvalidated) {

    public ReconciliationResult {
        resets = List.copyOf(resets);
        unitsNeedingRevert = List.copyOf(unitsNeedingRevert);
    }

    public boolean changedAnything() {
        return !resets.isEmpty() || baselineInvalidated;
    }

    /** One unit reset to PENDING, and why. */
    public record Reset(WorkUnitId id, String reason) {
        @Override
        public String toString() {
            return id + ": " + reason;
        }
    }
}
