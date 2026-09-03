package com.devmanchego.jtestforge.mutation;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * What one PIT run should analyse — jtestforge-specification.md §10.1, §13.2, §13.3.
 *
 * <p>Always scoped, never a bare "run PIT on the module": even the module-wide baseline
 * is a scope, just a wide one (§13.3's "always scope with targetClasses/targetTests" is
 * non-negotiable performance advice, not merely a per-unit optimisation).
 *
 * @param modulePath        the target module PIT runs against
 * @param targetClasses     PIT's {@code targetClasses} glob(s) - the classes to mutate
 * @param targetTests       PIT's {@code targetTests} glob(s) - the tests allowed to cover them
 * @param historyFile       incremental-analysis history file, read and rewritten by this
 *                          run; {@code null} only for a first-ever baseline where no
 *                          history exists yet to read (§13.3: "always pass the history
 *                          file" once one exists)
 * @param mutators          {@code harden.mutators}, e.g. {@code "DEFAULTS"}
 * @param timeout           hard ceiling on this invocation's wall-clock time
 */
public record MutationRequest(
        Path modulePath,
        List<String> targetClasses,
        List<String> targetTests,
        Path historyFile,
        String mutators,
        Duration timeout) {

    public MutationRequest {
        Objects.requireNonNull(modulePath, "modulePath");
        Objects.requireNonNull(mutators, "mutators");
        Objects.requireNonNull(timeout, "timeout");
        if (targetClasses == null || targetClasses.isEmpty()) {
            throw new IllegalArgumentException("targetClasses must not be empty - PIT must always be scoped (§13.3)");
        }
        if (targetTests == null || targetTests.isEmpty()) {
            throw new IllegalArgumentException("targetTests must not be empty - PIT must always be scoped (§13.3)");
        }
        targetClasses = List.copyOf(targetClasses);
        targetTests = List.copyOf(targetTests);
    }

    public Optional<Path> historyFileIfPresent() {
        return Optional.ofNullable(historyFile);
    }
}
