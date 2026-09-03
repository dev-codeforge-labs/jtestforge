package com.devmanchego.jtestforge.build;

import com.devmanchego.jtestforge.model.CompilerError;

import java.util.List;
import java.util.Objects;

/**
 * Outcome of one {@link MavenRunner} invocation: the raw process result plus whatever
 * javac diagnostics could be extracted from it.
 *
 * @param exitCode        the process exit code, or {@link com.devmanchego.jtestforge.util.ProcessResult#TIMED_OUT_EXIT_CODE}
 * @param timedOut        whether the run exceeded its timeout and was killed
 * @param stdout          full captured standard output
 * @param stderr          full captured standard error
 * @param compilerErrors  javac diagnostics found in the output; empty when the build had none
 */
public record MavenRunResult(
        int exitCode, boolean timedOut, String stdout, String stderr, List<CompilerError> compilerErrors) {

    public MavenRunResult {
        Objects.requireNonNull(stdout, "stdout");
        Objects.requireNonNull(stderr, "stderr");
        compilerErrors = compilerErrors == null ? List.of() : List.copyOf(compilerErrors);
    }

    public boolean succeeded() {
        return !timedOut && exitCode == 0;
    }
}
