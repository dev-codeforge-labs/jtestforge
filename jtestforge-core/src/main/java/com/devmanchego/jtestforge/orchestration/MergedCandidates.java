package com.devmanchego.jtestforge.orchestration;

import com.devmanchego.jtestforge.model.TestCandidate;

import java.util.List;
import java.util.Objects;

/**
 * What one successful merge wrote to the test file, and what a revert would have to take
 * back — jtestforge-specification.md §9.4 steps 3-6.
 *
 * <p>Public so a {@link UnitAcceptanceGate} - pass 1's coverage/gap check or pass 2's
 * mutant-kill check, whichever step 9 the run is using - can inspect what was actually
 * merged without {@code DefaultUnitProcessor} having to expose its whole internal attempt
 * machinery.
 *
 * @param candidates     the parsed methods that survived every guard and were merged
 * @param addedTestNames names of the test methods now in the file, for scoping the build
 *                       (§9.4 step 8) and for {@link com.devmanchego.jtestforge.analysis.TestClassReverter}
 * @param addedImports   imports the merge introduced, likewise needed for an exact revert
 */
public record MergedCandidates(List<TestCandidate> candidates, List<String> addedTestNames, List<String> addedImports)
        implements DefaultUnitProcessor.AttemptOutcome {

    public MergedCandidates {
        Objects.requireNonNull(candidates, "candidates");
        addedTestNames = addedTestNames == null ? List.of() : List.copyOf(addedTestNames);
        addedImports = addedImports == null ? List.of() : List.copyOf(addedImports);
    }
}
