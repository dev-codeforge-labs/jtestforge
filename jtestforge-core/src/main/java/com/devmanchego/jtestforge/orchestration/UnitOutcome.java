package com.devmanchego.jtestforge.orchestration;

import com.devmanchego.jtestforge.model.UnitStatus;

import java.util.List;
import java.util.Objects;

/**
 * What processing one work unit produced — the terminal state the engine then persists.
 *
 * <p>Never {@code IN_PROGRESS}: that status exists only as the write-ahead marker on disk
 * (§8.2.1), and a unit that has finished being processed always has a real answer, even
 * when the answer is that something threw.
 *
 * @param addedTests    test methods kept in the file, empty unless {@code status} is DONE
 * @param addedImports  imports introduced, recorded so a later revert can remove exactly
 *                      these and leave the developer's own imports alone
 * @param discarded     every candidate thrown away, with the reason, for §15's report
 * @param gapsClosed    pass 1: ids of framework-semantic gaps this unit actually closed
 * @param mutantsKilled pass 2: ids of mutants that went from surviving to killed
 */
public record UnitOutcome(
        UnitStatus status,
        List<String> addedTests,
        List<String> addedImports,
        List<String> discarded,
        List<String> gapsClosed,
        List<String> mutantsKilled,
        int linesCoveredDelta,
        int branchesCoveredDelta,
        String error) {

    public UnitOutcome {
        Objects.requireNonNull(status, "status");
        addedTests = addedTests == null ? List.of() : List.copyOf(addedTests);
        addedImports = addedImports == null ? List.of() : List.copyOf(addedImports);
        discarded = discarded == null ? List.of() : List.copyOf(discarded);
        gapsClosed = gapsClosed == null ? List.of() : List.copyOf(gapsClosed);
        mutantsKilled = mutantsKilled == null ? List.of() : List.copyOf(mutantsKilled);
    }

    /** Pass 1: no mutants involved. */
    public static UnitOutcome kept(List<String> addedTests, List<String> addedImports,
                                   List<String> discarded, List<String> gapsClosed,
                                   int linesCoveredDelta, int branchesCoveredDelta) {
        return kept(addedTests, addedImports, discarded, gapsClosed, List.of(),
                linesCoveredDelta, branchesCoveredDelta);
    }

    public static UnitOutcome kept(List<String> addedTests, List<String> addedImports,
                                   List<String> discarded, List<String> gapsClosed, List<String> mutantsKilled,
                                   int linesCoveredDelta, int branchesCoveredDelta) {
        return new UnitOutcome(UnitStatus.DONE, addedTests, addedImports, discarded, gapsClosed, mutantsKilled,
                linesCoveredDelta, branchesCoveredDelta, null);
    }

    public static UnitOutcome failed(UnitStatus status, String error, List<String> discarded) {
        return new UnitOutcome(status, List.of(), List.of(), discarded, List.of(), List.of(), 0, 0, error);
    }

    public boolean succeeded() {
        return status == UnitStatus.DONE;
    }
}
