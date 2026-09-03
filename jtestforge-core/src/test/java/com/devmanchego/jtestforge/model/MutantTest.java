package com.devmanchego.jtestforge.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link Mutant#stableId()} — jtestforge-implementation-plan.md phase 14: "mutant identity
 * is stable across two runs where unrelated lines moved (identity must not be raw line
 * number alone)".
 */
class MutantTest {

    @Test
    void identityIsUnchangedWhenOnlyTheLineNumberMoves() {
        // e.g. someone added a blank line above the mutated method between runs - the
        // method itself, and therefore this mutant, did not change.
        Mutant before = mutant(12, "MathMutator", List.of(3));
        Mutant after = mutant(15, "MathMutator", List.of(3));

        assertThat(after.stableId()).isEqualTo(before.stableId());
    }

    @Test
    void identityDiffersForADifferentMutatorOnTheSameLine() {
        Mutant math = mutant(12, "MathMutator", List.of(3));
        Mutant conditionals = mutant(12, "ConditionalsBoundaryMutator", List.of(3));

        assertThat(conditionals.stableId()).isNotEqualTo(math.stableId());
    }

    @Test
    void identityDiffersForADifferentBytecodeIndexOnTheSameLineAndMutator() {
        Mutant first = mutant(12, "MathMutator", List.of(3));
        Mutant second = mutant(12, "MathMutator", List.of(7));

        assertThat(second.stableId()).isNotEqualTo(first.stableId());
    }

    @Test
    void identityUsesTheMutatorsSimpleNameNotItsFullyQualifiedName() {
        // Readable in the unkillable.txt file a developer might hand-edit, without losing
        // uniqueness - two different mutators never share a simple name in PIT's own set.
        Mutant mutant = mutant(12, "MathMutator", List.of(3));

        assertThat(mutant.stableId()).contains("MathMutator").doesNotContain("org.pitest");
    }

    @Test
    void identityDistinguishesOverloadsByMethodDescriptor() {
        Mutant intOverload = new Mutant("com.acme.Calculator", "add", "(II)I", 12,
                "org.pitest.mutationtest.engine.gregor.mutators.MathMutator", List.of(3),
                MutationStatus.SURVIVED, null, "d");
        Mutant longOverload = new Mutant("com.acme.Calculator", "add", "(JJ)J", 12,
                "org.pitest.mutationtest.engine.gregor.mutators.MathMutator", List.of(3),
                MutationStatus.SURVIVED, null, "d");

        assertThat(intOverload.stableId()).isNotEqualTo(longOverload.stableId());
    }

    private Mutant mutant(int lineNumber, String mutatorSimpleName, List<Integer> indexes) {
        return new Mutant("com.acme.Calculator", "add", "(II)I", lineNumber,
                "org.pitest.mutationtest.engine.gregor.mutators." + mutatorSimpleName, indexes,
                MutationStatus.SURVIVED, null, "description");
    }
}
