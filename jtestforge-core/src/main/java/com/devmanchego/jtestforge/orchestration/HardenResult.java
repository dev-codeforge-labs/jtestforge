package com.devmanchego.jtestforge.orchestration;

import com.devmanchego.jtestforge.model.RunState;

import java.util.List;
import java.util.Objects;

/**
 * The outcome of one {@code harden} invocation — jtestforge-specification.md §10, §14.1.
 *
 * @param state              the run state as finally persisted
 * @param exitReason         which outcome this run reached
 * @param details            context for the reason: preflight failures, or the baseline
 *                           PIT error message
 * @param finalMutationScore killed mutants (baseline + this run) over the baseline's total
 *                           mutant count, 0.0-1.0 - {@code null} if no mutant was eligible
 *                           for mutation at all. Fully derivable from data this run already
 *                           holds (the baseline is one fixed, already-parsed
 *                           {@code MutationReport}), unlike a coverage percentage after
 *                           pass 1, which the state file has no way to compute (§15)
 */
public record HardenResult(RunState state, ExitReason exitReason, List<String> details, Double finalMutationScore) {

    public enum ExitReason {
        /** 0 - at least one mutant killed, or nothing eligible for mutation. */
        COMPLETED,
        /** 1 - the module did not build green, or the baseline PIT run itself failed. */
        PREFLIGHT_FAILED,
        /** 3 - every mutant group was left unkillable or otherwise failed. */
        NOTHING_KEPT,
        /** 4 - every attempted unit failed with a provider transport error. */
        PROVIDER_UNREACHABLE,
        /** 6 - the module's full suite is red at the end of the run. */
        FULL_SUITE_RED,
        /** 7 - the final mutation score is below harden.minMutationScore. */
        MUTATION_SCORE_BELOW_THRESHOLD
    }

    public HardenResult {
        Objects.requireNonNull(exitReason, "exitReason");
        details = details == null ? List.of() : List.copyOf(details);
    }

    /** Convenience for the common case, which never sets {@link #finalMutationScore}. */
    public HardenResult(RunState state, ExitReason exitReason, List<String> details) {
        this(state, exitReason, details, null);
    }

    public boolean succeeded() {
        return exitReason == ExitReason.COMPLETED;
    }
}
