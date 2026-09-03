package com.devmanchego.jtestforge.model;

import java.util.List;

/**
 * The complete, parsed result of one PIT run — jtestforge-specification.md §13.2.
 *
 * <p>Produced identically by either {@code MutationRunner} implementation
 * ({@code PitestMavenPluginRunner} or {@code StandaloneWrapperRunner}): both parse the
 * same {@code mutations.xml} shape, so the rest of the tool is unaware of which engine
 * ran (§13.2).
 */
public record MutationReport(List<Mutant> mutants) {

    public MutationReport {
        mutants = mutants == null ? List.of() : List.copyOf(mutants);
    }

    /**
     * The mutants worth generating a test for (§10.1 step 4): killed mutants need
     * nothing, and the remaining statuses (timed out, memory error, run error, non-viable)
     * are PIT/harness faults rather than suite gaps a generated test could close.
     */
    public List<Mutant> survivedOrUncovered() {
        return mutants.stream().filter(Mutant::isSurvivedOrUncovered).toList();
    }
}
