package com.devmanchego.jtestforge.model;

/**
 * How one test method resolved, per Surefire's XML report — jtestforge-implementation-plan.md
 * phase 7.
 *
 * <p>{@link #CONTEXT_LOAD_FAILURE} is split out from {@link #ERROR} on purpose: a Spring
 * {@code ApplicationContext} that fails to load is a different failure class from an
 * assertion the test itself made, and treating it as an ordinary assertion failure would
 * feed the {@code fixAssertion} prompt a stack trace about missing beans instead of about
 * the test's own logic - a repair attempt aimed at the wrong problem.
 */
public enum TestOutcome {
    PASSED,
    ASSERTION_FAILURE,
    CONTEXT_LOAD_FAILURE,
    ERROR,
    SKIPPED
}
