package com.devmanchego.jtestforge.model;

/**
 * Lifecycle state of one work unit — jtestforge-specification.md §8.1.
 *
 * <p>Each constant carries its own {@link RetryPolicy}, so the §8.1 table is a property
 * of the status rather than a switch statement repeated in every engine.
 */
public enum UnitStatus {

    /** Never attempted. */
    PENDING(RetryPolicy.ALWAYS),

    /**
     * Write-ahead marker, persisted <em>before</em> the AI is invoked (§8.2.1). Finding
     * this status in a loaded state file means a previous run died mid-unit.
     */
    IN_PROGRESS(RetryPolicy.ALWAYS),

    /** At least one generated test was kept. */
    DONE(RetryPolicy.NEVER),

    /** Attempts exhausted; the generated output never compiled. */
    FAILED_COMPILE(RetryPolicy.ONLY_WITH_RETRY_FAILED),

    /** Compiled, but the generated test never passed. */
    FAILED_ASSERTION(RetryPolicy.ONLY_WITH_RETRY_FAILED),

    /** Compiled and passed, but moved no metric — no coverage gain, no mutant killed. */
    DISCARDED_NO_VALUE(RetryPolicy.ONLY_WITH_RETRY_FAILED),

    /** Excluded by the {@code selection} rules. */
    SKIPPED_FILTERED(RetryPolicy.NEVER),

    /** Pass 2 only: mutant attempts exhausted; the mutant went to {@code unkillable.txt}. */
    SKIPPED_UNKILLABLE(RetryPolicy.NEVER),

    /** The unit's tier lacks a prerequisite (§7.3); the reason is recorded on the unit. */
    SKIPPED_TIER_UNAVAILABLE(RetryPolicy.IF_CLASSPATH_CHANGED),

    /**
     * Spring tiers: the candidate required a context-key change (§7.6). The class's
     * mock-bean set is re-synthesised once, after which the unit returns to
     * {@link #PENDING}.
     */
    ESCALATION_REQUIRED(RetryPolicy.ALWAYS),

    /** Transport failure after the provider's {@code transportRetries} were exhausted. */
    PROVIDER_ERROR(RetryPolicy.ALWAYS);

    private final RetryPolicy retryPolicy;

    UnitStatus(RetryPolicy retryPolicy) {
        this.retryPolicy = retryPolicy;
    }

    public RetryPolicy retryPolicy() {
        return retryPolicy;
    }

    /**
     * Whether a unit in this status should be re-attempted when the run resumes.
     *
     * @param retryFailedRequested the {@code --retry-failed} flag
     * @param classpathChanged     whether the module's test classpath differs from the
     *                             one seen when the unit was recorded
     */
    public boolean isRetriedOnResume(boolean retryFailedRequested, boolean classpathChanged) {
        return switch (retryPolicy) {
            case ALWAYS -> true;
            case NEVER -> false;
            case ONLY_WITH_RETRY_FAILED -> retryFailedRequested;
            case IF_CLASSPATH_CHANGED -> classpathChanged;
        };
    }

    /** Whether this is a terminal success — the unit contributed kept tests. */
    public boolean isSuccess() {
        return this == DONE;
    }
}
