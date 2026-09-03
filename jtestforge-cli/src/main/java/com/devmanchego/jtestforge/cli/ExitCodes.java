package com.devmanchego.jtestforge.cli;

/** The exit codes of jtestforge-specification.md §14.1, named instead of scattered as literals. */
final class ExitCodes {

    static final int SUCCESS = 0;
    static final int CONFIGURATION_OR_PREFLIGHT_ERROR = 1;
    static final int LOCK_HELD = 2;
    static final int NOTHING_KEPT = 3;
    static final int PROVIDER_UNREACHABLE = 4;
    static final int ABORTED_ON_CONSECUTIVE_FAILURES = 5;
    static final int FULL_SUITE_RED = 6;
    static final int MUTATION_SCORE_BELOW_THRESHOLD = 7;
    static final int CONTEXT_LOAD_BUDGET_EXHAUSTED = 8;
    static final int INTERRUPTED = 130;

    private ExitCodes() {
    }
}
