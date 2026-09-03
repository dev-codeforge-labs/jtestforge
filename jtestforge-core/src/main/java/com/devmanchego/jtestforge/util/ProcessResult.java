package com.devmanchego.jtestforge.util;

/**
 * Outcome of a process invocation via {@link ProcessRunner}.
 *
 * @param exitCode   the process exit code, or {@link #TIMED_OUT_EXIT_CODE} if the
 *                    process had to be killed after exceeding its timeout
 * @param stdout     full captured standard output
 * @param stderr     full captured standard error
 * @param timedOut   {@code true} if the process was forcibly destroyed after exceeding
 *                    its timeout
 */
public record ProcessResult(int exitCode, String stdout, String stderr, boolean timedOut) {

    public static final int TIMED_OUT_EXIT_CODE = -1;

    public boolean succeeded() {
        return !timedOut && exitCode == 0;
    }
}
