package com.devmanchego.jtestforge.guard;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * §11.1 guard 5, the one guard that runs <b>after</b> the candidate has been verified
 * rather than before it is written: deciding whether a test covers anything new needs its
 * actual covered-line set, which only exists once it has run.
 *
 * <p>This is the guard that stops a second run over the same class inflating the suite
 * with near-copies - the failure mode that makes tools like this get abandoned.
 */
class SemanticDuplicateGuardTest {

    private final SemanticDuplicateGuard guard = new SemanticDuplicateGuard();

    @Test
    void aCandidateCoveringLinesNoExistingTestReachesIsKept() {
        Optional<GuardRejection> rejection = guard.evaluate("coversSomethingNew",
                Set.of(10, 11, 12), List.of(Set.of(10, 11)), false);

        assertThat(rejection).isEmpty();
    }

    @Test
    void aCandidateWhoseCoverageIsASubsetOfAnExistingTestIsRejected() {
        Optional<GuardRejection> rejection = guard.evaluate("coversNothingNew",
                Set.of(10, 11), List.of(Set.of(10, 11, 12)), false);

        assertThat(rejection).isPresent();
        assertThat(rejection.get().guardId()).isEqualTo(GuardId.SEMANTIC_DUPLICATE);
        assertThat(rejection.get().methodName()).isEqualTo("coversNothingNew");
    }

    @Test
    void anExactlyEqualCoverageSetIsAlsoASubsetAndIsRejected() {
        assertThat(guard.evaluate("identicalCoverage",
                Set.of(10, 11), List.of(Set.of(10, 11)), false)).isPresent();
    }

    @Test
    void aSubsetThatKillsANewMutantIsKeptDespiteCoveringNothingNew() {
        // The whole point of the mutation pass: a test can cover exactly the same lines
        // as an existing one and still be the only thing that would catch a defect there.
        // Rejecting it on coverage alone would discard precisely what `harden` is for.
        Optional<GuardRejection> rejection = guard.evaluate("killsAMutantOnAlreadyCoveredLines",
                Set.of(10, 11), List.of(Set.of(10, 11, 12)), true);

        assertThat(rejection).isEmpty();
    }

    @Test
    void aCandidateIsComparedAgainstEveryExistingTestNotJustTheFirst() {
        Optional<GuardRejection> rejection = guard.evaluate("subsetOfTheThirdTest",
                Set.of(20, 21), List.of(Set.of(1, 2), Set.of(10, 11), Set.of(20, 21, 22)), false);

        assertThat(rejection).isPresent();
    }

    @Test
    void aCandidateThatIsNoSubsetOfAnySingleTestIsKeptEvenIfTheUnionCoversIt() {
        // Being a subset of the union is not the same as duplicating any one test: a
        // single test spanning both regions may be the only one exercising them together.
        Optional<GuardRejection> rejection = guard.evaluate("spansTwoRegions",
                Set.of(10, 20), List.of(Set.of(10, 11), Set.of(20, 21)), false);

        assertThat(rejection).isEmpty();
    }

    @Test
    void aCandidateThatCoveredNothingAtAllIsRejected() {
        // An empty set is trivially a subset of everything, and a test covering no lines
        // of the target cannot be doing anything useful.
        assertThat(guard.evaluate("coveredNothing", Set.of(), List.of(Set.of(10)), false)).isPresent();
    }

    @Test
    void theFirstTestInAClassIsKeptBecauseThereIsNothingToDuplicate() {
        assertThat(guard.evaluate("theOnlyTest", Set.of(10, 11), List.of(), false)).isEmpty();
    }
}
