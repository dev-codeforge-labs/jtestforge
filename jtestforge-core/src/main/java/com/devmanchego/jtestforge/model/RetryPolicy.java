package com.devmanchego.jtestforge.model;

/**
 * Whether a unit in a given {@link UnitStatus} is re-attempted when a run is resumed.
 *
 * <p>This encodes the "Retried on resume?" column of jtestforge-specification.md §8.1 as
 * executable policy rather than prose, so the resume decision has exactly one definition
 * that both {@code ResumeReconciler} and the pass engines consult.
 */
public enum RetryPolicy {
    /** Re-attempted on every resume. */
    ALWAYS,
    /** Re-attempted only when the user passes {@code --retry-failed}. */
    ONLY_WITH_RETRY_FAILED,
    /** Never re-attempted: the outcome is final for this run. */
    NEVER,
    /**
     * Re-attempted only if the module's test classpath changed since the unit was
     * skipped — the prerequisite that was missing (§7.3) may now be present.
     */
    IF_CLASSPATH_CHANGED
}
