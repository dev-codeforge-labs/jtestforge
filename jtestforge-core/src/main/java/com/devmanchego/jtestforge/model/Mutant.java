package com.devmanchego.jtestforge.model;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * One mutation PIT applied and the outcome of running the covering tests against it —
 * jtestforge-specification.md §10.1, §13.2.
 *
 * @param mutatedClass      fully-qualified class the mutation was applied to
 * @param mutatedMethod     the method mutated
 * @param methodDescription JVM method descriptor, e.g. {@code (II)I} - together with the
 *                          method name this disambiguates overloads, exactly as
 *                          {@code CoverageMethodJoiner} already relies on for JaCoCo
 * @param lineNumber        source line PIT reports for the mutation; supplied to the
 *                          model as location context only (§10.2) - never part of
 *                          {@link #stableId()}, since it shifts when code elsewhere in
 *                          the file moves
 * @param mutator           fully-qualified PIT mutator class name
 * @param indexes           bytecode instruction index/indexes the mutation touched
 * @param status            what happened when the covering tests re-ran against it
 * @param killingTest        the test that killed it, or {@code null} if it was not killed
 * @param description       PIT's own one-line description of the mutation
 */
public record Mutant(
        String mutatedClass,
        String mutatedMethod,
        String methodDescription,
        int lineNumber,
        String mutator,
        List<Integer> indexes,
        MutationStatus status,
        String killingTest,
        String description) {

    public Mutant {
        Objects.requireNonNull(mutatedClass, "mutatedClass");
        Objects.requireNonNull(mutatedMethod, "mutatedMethod");
        Objects.requireNonNull(methodDescription, "methodDescription");
        Objects.requireNonNull(mutator, "mutator");
        Objects.requireNonNull(status, "status");
        indexes = indexes == null ? List.of() : List.copyOf(indexes);
    }

    public boolean isSurvivedOrUncovered() {
        return status == MutationStatus.SURVIVED || status == MutationStatus.NO_COVERAGE;
    }

    /**
     * A stable identity for the unkillable list (§10.1 step 6) - deliberately excludes
     * {@link #lineNumber()}. A mutant's line number shifts whenever code elsewhere in the
     * file is added or removed, which would otherwise silently drop it off the unkillable
     * list (or, worse, collide with an unrelated mutant that happened to land on the same
     * line afterwards) even though nothing about the mutation itself changed. The method's
     * descriptor plus its bytecode instruction indexes stay stable across unrelated edits,
     * as long as the mutated method itself is untouched.
     */
    public String stableId() {
        String indexPart = indexes.stream().map(String::valueOf).collect(Collectors.joining(","));
        return mutatedClass + "#" + mutatedMethod + methodDescription + "#" + simpleMutatorName() + "#" + indexPart;
    }

    private String simpleMutatorName() {
        int lastDot = mutator.lastIndexOf('.');
        return lastDot < 0 ? mutator : mutator.substring(lastDot + 1);
    }
}
