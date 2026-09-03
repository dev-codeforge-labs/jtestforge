package com.devmanchego.jtestforge.model;

/**
 * PIT's {@code detected}/{@code status} outcome for one mutant, as written to
 * {@code mutations.xml} — jtestforge-specification.md §10.1, §13.2.
 */
public enum MutationStatus {
    /** A test failed because of this mutation - the mutant is dead. */
    KILLED,
    /** Every covering test still passed - the behaviour this mutant changed is unasserted. */
    SURVIVED,
    /** No test executed the mutated line at all - the strongest possible "unasserted". */
    NO_COVERAGE,
    /** A covering test hung; PIT killed it. Treated like SURVIVED for triage purposes. */
    TIMED_OUT,
    /** A covering test exhausted memory. Likewise treated like SURVIVED. */
    MEMORY_ERROR,
    /** PIT itself failed to run the mutant (a harness fault, not a suite gap). */
    RUN_ERROR,
    /** The mutation produced bytecode the JVM rejects; PIT excludes it from scoring. */
    NON_VIABLE
}
