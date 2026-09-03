package com.devmanchego.jtestforge.orchestration;

import com.devmanchego.jtestforge.model.RunState;

import java.util.List;
import java.util.Objects;

/**
 * The outcome of one {@code generate} invocation — maps to the exit codes of
 * jtestforge-specification.md §14.1.
 *
 * @param state              the run state as finally persisted
 * @param exitReason         which §14.1 outcome this run reached
 * @param fullSuiteFailures  tests failing at the end that were not failing at the start;
 *                           §9.5's loud report when scoped-green turns full-red
 * @param contextForkedClasses test classes whose Spring context cache key changed
 *                           partway through the run; populated only for
 *                           {@link ExitReason#CONTEXT_LOAD_BUDGET_EXHAUSTED} (§9.6)
 */
public record GenerateResult(
        RunState state, ExitReason exitReason, List<String> fullSuiteFailures,
        List<String> contextForkedClasses) {

    /** Maps 1:1 onto the exit codes in §14.1. */
    public enum ExitReason {
        /** 0 - at least one test kept, or nothing to do. */
        COMPLETED,
        /** 1 - the module did not build green before the run started (§2). */
        PREFLIGHT_FAILED,
        /** 3 - every unit was discarded or failed. */
        NOTHING_KEPT,
        /** 4 - every attempted unit failed with a provider transport error. */
        PROVIDER_UNREACHABLE,
        /** 5 - aborted by execution.consecutiveFailureAbort. */
        ABORTED_ON_CONSECUTIVE_FAILURES,
        /** 6 - the module's full suite is red at the end of the run. */
        FULL_SUITE_RED,
        /** 8 - spring.maxContextLoadsPerRun exhausted (§9.6). */
        CONTEXT_LOAD_BUDGET_EXHAUSTED
    }

    public GenerateResult {
        Objects.requireNonNull(exitReason, "exitReason");
        fullSuiteFailures = fullSuiteFailures == null ? List.of() : List.copyOf(fullSuiteFailures);
        contextForkedClasses = contextForkedClasses == null ? List.of() : List.copyOf(contextForkedClasses);
    }

    /** Convenience for the common case, which never sets {@link #contextForkedClasses}. */
    public GenerateResult(RunState state, ExitReason exitReason, List<String> fullSuiteFailures) {
        this(state, exitReason, fullSuiteFailures, List.of());
    }

    public boolean succeeded() {
        return exitReason == ExitReason.COMPLETED;
    }
}
