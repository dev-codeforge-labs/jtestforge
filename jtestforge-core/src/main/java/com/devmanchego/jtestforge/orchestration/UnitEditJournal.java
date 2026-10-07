package com.devmanchego.jtestforge.orchestration;

import java.util.List;

/**
 * Where a unit announces what it is about to leave in its test file, <em>before</em> it writes
 * it - jtestforge-specification.md §8.2.1's write-ahead discipline, extended from "this unit
 * started" to "this is what it has merged".
 *
 * <p>Without it, a unit's {@code addedTests}/{@code addedImports} only reached the state file
 * when the unit finished, so a process killed mid-unit left an {@code IN_PROGRESS} unit that
 * recorded nothing: the shutdown hook and resume reconciliation "reverted" an empty list and
 * the merged methods stayed in the file for good.
 *
 * <p>Recording first and writing second is what makes a kill at any instant recoverable: if
 * the process dies between the two, the journal names methods the file does not contain yet,
 * and reverting a method that is not there is a no-op.
 */
@FunctionalInterface
public interface UnitEditJournal {

    /** For callers that keep no state file - tests, and the mutation pass for now. */
    UnitEditJournal NONE = (addedTests, addedImports) -> {
    };

    /**
     * @param addedTests   every test method the unit has merged and not yet reverted - the whole
     *                     set, not a delta; empty once the unit has reverted everything
     * @param addedImports the imports that came with them, likewise
     */
    void record(List<String> addedTests, List<String> addedImports);
}
