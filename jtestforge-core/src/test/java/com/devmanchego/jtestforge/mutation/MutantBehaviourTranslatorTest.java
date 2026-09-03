package com.devmanchego.jtestforge.mutation;

import com.devmanchego.jtestforge.model.Mutant;
import com.devmanchego.jtestforge.model.MutationStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * §10.2's mutator-to-behaviour table - the most important design decision in pass 2,
 * and the one place a raw PIT operator description must never reach a prompt.
 */
class MutantBehaviourTranslatorTest {

    private final MutantBehaviourTranslator translator = new MutantBehaviourTranslator();

    /** Every mutator family named in jtestforge-specification.md §10.2's table. */
    private static final List<String> SPEC_TABLE_MUTATORS = List.of(
            "org.pitest.mutationtest.engine.gregor.mutators.ConditionalsBoundaryMutator",
            "org.pitest.mutationtest.engine.gregor.mutators.NegateConditionalsMutator",
            "org.pitest.mutationtest.engine.gregor.mutators.MathMutator",
            "org.pitest.mutationtest.engine.gregor.mutators.IncrementsMutator",
            "org.pitest.mutationtest.engine.gregor.mutators.returns.PrimitiveReturnsMutator",
            "org.pitest.mutationtest.engine.gregor.mutators.returns.BooleanFalseReturnValsMutator",
            "org.pitest.mutationtest.engine.gregor.mutators.returns.BooleanTrueReturnValsMutator",
            "org.pitest.mutationtest.engine.gregor.mutators.returns.EmptyObjectReturnValsMutator",
            "org.pitest.mutationtest.engine.gregor.mutators.returns.NullReturnValsMutator",
            "org.pitest.mutationtest.engine.gregor.mutators.VoidMethodCallMutator",
            "org.pitest.mutationtest.engine.gregor.mutators.InvertNegsMutator",
            "org.pitest.mutationtest.engine.gregor.mutators.ConstructorCallMutator",
            "org.pitest.mutationtest.engine.gregor.mutators.NonVoidMethodCallMutator");

    @Test
    void everyDefaultMutatorHasARealTranslationRatherThanTheFallback() {
        for (String mutatorFqn : SPEC_TABLE_MUTATORS) {
            String translation = translator.translate(mutant(mutatorFqn, 12));
            assertThat(translation).as("translation for %s", mutatorFqn)
                    .doesNotContain(MutantBehaviourTranslator.GENERIC_FALLBACK);
        }
    }

    @Test
    void anUnrecognisedMutatorFallsBackToAGenericBehaviouralSentenceRatherThanNothing() {
        String translation = translator.translate(mutant(
                "org.pitest.mutationtest.engine.gregor.mutators.experimental.SomeFutureMutator", 12));

        assertThat(translation).contains(MutantBehaviourTranslator.GENERIC_FALLBACK);
    }

    @Test
    void theBoundaryMutatorTranslationMatchesTheSpecTableExactly() {
        String translation = translator.translate(mutant(
                "org.pitest.mutationtest.engine.gregor.mutators.ConditionalsBoundaryMutator", 47));

        assertThat(translation).isEqualTo("The boundary of this comparison is not verified: no test "
                + "distinguishes the exact boundary value from the value just past it. (around line 47.)");
    }

    @Test
    void theFiveReturnValueMutatorsAllShareTheSameBehaviouralDescription() {
        String primitive = translator.translate(mutant(
                "org.pitest.mutationtest.engine.gregor.mutators.returns.PrimitiveReturnsMutator", 1));
        String nullReturn = translator.translate(mutant(
                "org.pitest.mutationtest.engine.gregor.mutators.returns.NullReturnValsMutator", 1));

        assertThat(primitive).isEqualTo(nullReturn);
        assertThat(primitive).contains("The returned value is not asserted for this path");
    }

    @Test
    void constructorCallAndNonVoidMethodCallShareTheCollaboratorInteractionDescription() {
        String constructor = translator.translate(mutant(
                "org.pitest.mutationtest.engine.gregor.mutators.ConstructorCallMutator", 1));
        String nonVoid = translator.translate(mutant(
                "org.pitest.mutationtest.engine.gregor.mutators.NonVoidMethodCallMutator", 1));

        assertThat(constructor).isEqualTo(nonVoid);
    }

    @Test
    void theTranslationNeverIncorporatesPitsOwnRawOperatorDescription() {
        // translate() must read only mutator() and lineNumber() - never description(), the
        // one field where PIT's literal "replaced >= with >" text lives.
        Mutant mutant = new Mutant("com.acme.Calculator", "classify", "(I)I", 20,
                "org.pitest.mutationtest.engine.gregor.mutators.ConditionalsBoundaryMutator",
                List.of(5), MutationStatus.SURVIVED, null,
                "changed conditional boundary >= to > EXTREMELY_SUSPICIOUS_RAW_OPERATOR_TEXT");

        String translation = translator.translate(mutant);

        assertThat(translation).doesNotContain("EXTREMELY_SUSPICIOUS_RAW_OPERATOR_TEXT")
                .doesNotContain(">=");
    }

    @Test
    void theLineNumberIsSuppliedAsLocationContext() {
        String translation = translator.translate(mutant(
                "org.pitest.mutationtest.engine.gregor.mutators.MathMutator", 99));

        assertThat(translation).contains("line 99");
    }

    // --- grouping -----------------------------------------------------------------------

    @Test
    void mutantsAreGroupedByClassAndMethodInEncounterOrder() {
        Mutant classifyA = mutant("com.acme.Calculator", "classify", "(I)I", 10, "MathMutator");
        Mutant addA = mutant("com.acme.Calculator", "add", "(II)I", 20, "MathMutator");
        Mutant classifyB = mutant("com.acme.Calculator", "classify", "(I)I", 11, "IncrementsMutator");

        List<List<Mutant>> groups = translator.group(List.of(classifyA, addA, classifyB), 10);

        assertThat(groups).hasSize(2);
        assertThat(groups.get(0)).containsExactly(classifyA, classifyB);
        assertThat(groups.get(1)).containsExactly(addA);
    }

    @Test
    void aMethodWithMoreMutantsThanTheCapIsSplitIntoConsecutiveChunks() {
        List<Mutant> fiveMutants = List.of(
                mutant("com.acme.Calculator", "classify", "(I)I", 10, "MathMutator"),
                mutant("com.acme.Calculator", "classify", "(I)I", 11, "MathMutator"),
                mutant("com.acme.Calculator", "classify", "(I)I", 12, "MathMutator"),
                mutant("com.acme.Calculator", "classify", "(I)I", 13, "MathMutator"),
                mutant("com.acme.Calculator", "classify", "(I)I", 14, "MathMutator"));

        List<List<Mutant>> groups = translator.group(fiveMutants, 2);

        assertThat(groups).hasSize(3);
        assertThat(groups.get(0)).hasSize(2);
        assertThat(groups.get(1)).hasSize(2);
        assertThat(groups.get(2)).hasSize(1);
    }

    @Test
    void distinctOverloadsOfTheSameMethodNameFormSeparateGroups() {
        Mutant intOverload = mutant("com.acme.Calculator", "add", "(II)I", 10, "MathMutator");
        Mutant longOverload = mutant("com.acme.Calculator", "add", "(JJ)J", 20, "MathMutator");

        List<List<Mutant>> groups = translator.group(List.of(intOverload, longOverload), 10);

        assertThat(groups).hasSize(2);
    }

    @Test
    void anEmptyMutantListGroupsToNoGroupsAtAll() {
        assertThat(translator.group(List.of(), 6)).isEmpty();
    }

    private Mutant mutant(String mutatorFqn, int lineNumber) {
        return mutant("com.acme.Calculator", "classify", "(I)I", lineNumber, mutatorFqn);
    }

    private Mutant mutant(String mutatedClass, String mutatedMethod, String methodDescription,
                          int lineNumber, String mutatorFqn) {
        String fullyQualified = mutatorFqn.contains(".")
                ? mutatorFqn
                : "org.pitest.mutationtest.engine.gregor.mutators." + mutatorFqn;
        return new Mutant(mutatedClass, mutatedMethod, methodDescription, lineNumber,
                fullyQualified, List.of(1), MutationStatus.SURVIVED, null, "description");
    }
}
