package com.devmanchego.jtestforge.orchestration;

import java.util.List;
import java.util.Objects;

/**
 * Step 9's decision, whichever pass is asking — jtestforge-specification.md §9.4 step 9
 * (coverage/gap) or §10.1 step 5 (mutant kill). The one point where the otherwise
 * identical per-unit loop of the two passes diverges.
 *
 * @param keep               whether the merged tests stay in the file
 * @param reason             why, in words, for the run log and the unit's recorded outcome
 * @param gapsClosed         pass 1 only: ids of framework-semantic gaps this unit closed
 * @param mutantsKilled      pass 2 only: ids of mutants that went from surviving to killed
 * @param linesCoveredDelta  pass 1 only: new lines covered
 * @param branchesCoveredDelta pass 1 only: new branches covered
 */
public record AcceptanceVerdict(
        boolean keep, String reason, List<String> gapsClosed, List<String> mutantsKilled,
        int linesCoveredDelta, int branchesCoveredDelta) {

    public AcceptanceVerdict {
        Objects.requireNonNull(reason, "reason");
        gapsClosed = gapsClosed == null ? List.of() : List.copyOf(gapsClosed);
        mutantsKilled = mutantsKilled == null ? List.of() : List.copyOf(mutantsKilled);
    }

    public static AcceptanceVerdict keep(String reason, List<String> gapsClosed, List<String> mutantsKilled,
                                         int linesCoveredDelta, int branchesCoveredDelta) {
        return new AcceptanceVerdict(true, reason, gapsClosed, mutantsKilled, linesCoveredDelta, branchesCoveredDelta);
    }

    public static AcceptanceVerdict discard(String reason) {
        return new AcceptanceVerdict(false, reason, List.of(), List.of(), 0, 0);
    }
}
