package com.devmanchego.jtestforge.mutation;

import com.devmanchego.jtestforge.model.Mutant;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Translates a surviving mutant into the behaviour it proves is unverified —
 * jtestforge-specification.md §10.2. The most important design decision in pass 2, and
 * deliberately dumb: a fixed table lookup, no tokens, no latency, no model call.
 *
 * <p>A mutant must never reach the model as itself. "Write a test that kills the mutant
 * replacing {@code >=} with {@code >} on line 47" produces a test coupled to the
 * implementation - exactly the kind of test that makes a suite expensive and brittle.
 * {@link #translate(Mutant)} therefore reads only {@link Mutant#mutator()} and
 * {@link Mutant#lineNumber()} - never {@link Mutant#description()}, which is where PIT's
 * own operator-level text lives - so there is no code path through which a raw mutation
 * operator could leak into a prompt.
 *
 * <p>Matched by keyword against the mutator's fully-qualified class name rather than an
 * exact simple-name lookup: PIT's mutator class names have shifted slightly across major
 * versions (singular/plural, an "experimental" or "returns" sub-package), and a keyword
 * that is a substring of every variant is more robust than pinning one spelling. Every
 * keyword is an unambiguous, PascalCase token that cannot collide with the lowercase
 * package segments the FQN is otherwise built from.
 */
public final class MutantBehaviourTranslator {

    /** Unlisted or unrecognised mutators still get a behavioural sentence, never nothing. */
    static final String GENERIC_FALLBACK =
            "This code path's behaviour is not independently verified by any current assertion.";

    private static final String RETURNED_VALUE_GAP =
            "The returned value is not asserted for this path - a default/empty/null return "
                    + "would pass every current test.";
    private static final String COLLABORATOR_INTERACTION_GAP =
            "The collaborator interaction on this path is not observed by any assertion or verification.";

    /** jtestforge-specification.md §10.2's table, keyed by a keyword unique to each family. */
    private static final Map<String, String> FAMILIES = new LinkedHashMap<>();

    static {
        FAMILIES.put("ConditionalsBoundary",
                "The boundary of this comparison is not verified: no test distinguishes the "
                        + "exact boundary value from the value just past it.");
        FAMILIES.put("NegateConditionals",
                "Both outcomes of this condition are not independently verified: some branch "
                        + "produces the same observable result either way.");
        FAMILIES.put("Math",
                "The result of this arithmetic is not asserted precisely enough - a different "
                        + "operator would produce the same observed outcome.");
        FAMILIES.put("Increments",
                "The number of iterations / the increment step is not observable in any assertion.");
        FAMILIES.put("PrimitiveReturns", RETURNED_VALUE_GAP);
        FAMILIES.put("FalseReturn", RETURNED_VALUE_GAP);
        FAMILIES.put("TrueReturn", RETURNED_VALUE_GAP);
        FAMILIES.put("EmptyReturn", RETURNED_VALUE_GAP);
        FAMILIES.put("EmptyObjectReturn", RETURNED_VALUE_GAP);
        FAMILIES.put("NullReturn", RETURNED_VALUE_GAP);
        FAMILIES.put("ReturnVals", RETURNED_VALUE_GAP);
        FAMILIES.put("InvertNegs", "The sign of this value is not asserted.");
        // Checked before "VoidMethodCall": NonVoidMethodCallMutator's own name contains
        // "VoidMethodCall" as a substring ("Non" + "VoidMethodCall" + "Mutator"), so the
        // more specific keyword must be matched first or every non-void-call mutant would
        // be mistranslated as a void one - caught by this class's own test suite.
        FAMILIES.put("ConstructorCall", COLLABORATOR_INTERACTION_GAP);
        FAMILIES.put("NonVoidMethodCall", COLLABORATOR_INTERACTION_GAP);
        FAMILIES.put("VoidMethodCall",
                "This side effect is never observed: removing the call entirely would not fail any test.");
    }

    /** One mutant's behavioural gap, with its line as location context (§10.2). */
    public String translate(Mutant mutant) {
        return descriptionFor(mutant.mutator()) + " (around line " + mutant.lineNumber() + ".)";
    }

    public List<String> translateAll(List<Mutant> mutants) {
        return mutants.stream().map(this::translate).toList();
    }

    private String descriptionFor(String mutatorFqn) {
        for (Map.Entry<String, String> family : FAMILIES.entrySet()) {
            if (mutatorFqn.contains(family.getKey())) {
                return family.getValue();
            }
        }
        return GENERIC_FALLBACK;
    }

    /**
     * Groups surviving mutants by (class, method), preserving encounter order, then splits
     * any group larger than {@code maxMutantsPerPrompt} into consecutive chunks - §10.1
     * step 4. A method with more surviving mutants than the cap gets more than one unit
     * rather than one prompt asked to fix an unbounded number of behaviours at once.
     */
    public List<List<Mutant>> group(List<Mutant> mutants, int maxMutantsPerPrompt) {
        if (maxMutantsPerPrompt <= 0) {
            throw new IllegalArgumentException("maxMutantsPerPrompt must be positive: " + maxMutantsPerPrompt);
        }
        Map<String, List<Mutant>> byMethod = new LinkedHashMap<>();
        for (Mutant mutant : mutants) {
            byMethod.computeIfAbsent(methodKey(mutant), key -> new ArrayList<>()).add(mutant);
        }

        List<List<Mutant>> groups = new ArrayList<>();
        for (List<Mutant> methodMutants : byMethod.values()) {
            for (int start = 0; start < methodMutants.size(); start += maxMutantsPerPrompt) {
                int end = Math.min(start + maxMutantsPerPrompt, methodMutants.size());
                groups.add(List.copyOf(methodMutants.subList(start, end)));
            }
        }
        return List.copyOf(groups);
    }

    private String methodKey(Mutant mutant) {
        return mutant.mutatedClass() + "#" + mutant.mutatedMethod() + mutant.methodDescription();
    }
}
