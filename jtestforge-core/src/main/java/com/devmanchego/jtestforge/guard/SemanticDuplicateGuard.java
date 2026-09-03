package com.devmanchego.jtestforge.guard;

import java.util.Collection;
import java.util.Optional;
import java.util.Set;

/**
 * Guard 5 of jtestforge-specification.md §11.1 — the only guard that runs <b>after</b> a
 * candidate has been verified rather than before it is written, because deciding whether
 * a test covers anything new needs the covered-line set it actually produced.
 *
 * <p>Without this check, a second run over the same class inflates its suite with
 * near-copies of tests that are already there - the failure mode that gets tools like
 * this abandoned.
 *
 * <p>Two deliberate subtleties:
 *
 * <ul>
 *   <li><b>Killing a new mutant overrides coverage.</b> A test can cover exactly the same
 *       lines as an existing one and still be the only thing that would catch a defect in
 *       them. Rejecting it on coverage alone would discard precisely what {@code harden}
 *       exists to produce (§10.3).</li>
 *   <li><b>Subset of one test, never of the union.</b> A candidate spanning two regions
 *       that two separate tests each cover half of is not a duplicate: it may be the only
 *       test exercising them together, which is where interaction defects live.</li>
 * </ul>
 */
public final class SemanticDuplicateGuard {

    /**
     * @param candidateCoveredLines     lines the candidate covered when it ran
     * @param existingTestCoveredLines  the same, one set per test already in the class
     * @param killedANewMutant          whether the candidate killed a mutant that was
     *                                  surviving before it was added
     */
    public Optional<GuardRejection> evaluate(
            String candidateMethodName, Set<Integer> candidateCoveredLines,
            Collection<Set<Integer>> existingTestCoveredLines, boolean killedANewMutant) {

        if (killedANewMutant) {
            return Optional.empty();
        }
        if (candidateCoveredLines.isEmpty()) {
            return Optional.of(new GuardRejection(GuardId.SEMANTIC_DUPLICATE, candidateMethodName,
                    "the test covered no lines of the method under test at all, so it cannot be "
                            + "exercising anything"));
        }
        for (Set<Integer> existing : existingTestCoveredLines) {
            if (existing.containsAll(candidateCoveredLines)) {
                return Optional.of(new GuardRejection(GuardId.SEMANTIC_DUPLICATE, candidateMethodName,
                        "every line this test covers is already covered by an existing test, and it "
                                + "kills no mutant that was surviving - it adds nothing the suite did "
                                + "not already have"));
            }
        }
        return Optional.empty();
    }
}
