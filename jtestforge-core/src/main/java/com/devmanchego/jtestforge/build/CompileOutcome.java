package com.devmanchego.jtestforge.build;

import com.devmanchego.jtestforge.model.CompilerError;

import java.util.List;

/**
 * Result of compiling the module's tests — jtestforge-specification.md §9.4 step 7.
 *
 * @param compiled whether {@code test-compile} succeeded
 * @param errors   javac diagnostics, empty when it did or when the build failed for a
 *                 reason {@link CompilerErrorParser} does not recognise as one (a plugin
 *                 failure, an annotation processor error, an unfamiliar diagnostic format)
 * @param rawLog   the failed build's complete stdout and stderr, empty on success. Kept
 *                  even when {@code errors} is non-empty: a genuine gap in
 *                  {@link CompilerErrorParser}'s coverage must never silently leave neither
 *                  the model nor a human anything to act on.
 */
public record CompileOutcome(boolean compiled, List<CompilerError> errors, String rawLog) {

    public CompileOutcome {
        errors = errors == null ? List.of() : List.copyOf(errors);
        rawLog = rawLog == null ? "" : rawLog;
    }

    public static CompileOutcome success() {
        return new CompileOutcome(true, List.of(), "");
    }

    public static CompileOutcome failure(List<CompilerError> errors, String rawLog) {
        return new CompileOutcome(false, errors, rawLog);
    }

    /** For callers (tests, mainly) that have diagnostics already parsed and no raw log to keep. */
    public static CompileOutcome failure(List<CompilerError> errors) {
        return failure(errors, "");
    }
}
