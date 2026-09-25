package com.devmanchego.jtestforge.build;

import com.devmanchego.jtestforge.util.ExecutableResolver;
import com.devmanchego.jtestforge.util.JavaHomeEnvironment;
import com.devmanchego.jtestforge.util.ProcessResult;
import com.devmanchego.jtestforge.util.ProcessRunner;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Runs Maven as a child process — jtestforge-specification.md §13.1. Always a child
 * process, never an embedded Maven, so the target module's own toolchain and settings
 * govern exactly as they would from a developer's own shell.
 *
 * <p>Every invocation this class makes uses the same shape:
 * {@code [resolved mavenExecutable] + mavenArgs + goals}, working directory
 * {@code modulePath}, {@code JAVA_HOME} (and {@code PATH}, see
 * {@link JavaHomeEnvironment}) set from configuration when given - so the configured JDK
 * wins even over a launcher script that resolves {@code java} off {@code PATH} rather than
 * honouring {@code JAVA_HOME} itself.
 *
 * <p>Timeout composition - adding {@code spring.contextLoadTimeoutSeconds} on top of the
 * base build timeout for a Spring-tier unit - is the caller's decision, not this class's:
 * the orchestration layer (implementation phases 12-13) knows which unit is running and
 * at which tier, and passes whatever total {@link Duration} it computed. This class stays
 * tier-agnostic.
 */
public final class MavenRunner {

    private final ProcessRunner processRunner;
    private final String mavenExecutable;
    private final List<String> mavenArgs;
    private final Path javaHome;

    public MavenRunner(ProcessRunner processRunner, String mavenExecutable, List<String> mavenArgs) {
        this(processRunner, mavenExecutable, mavenArgs, null);
    }

    public MavenRunner(ProcessRunner processRunner, String mavenExecutable, List<String> mavenArgs, Path javaHome) {
        this.processRunner = Objects.requireNonNull(processRunner, "processRunner");
        this.mavenExecutable = Objects.requireNonNull(mavenExecutable, "mavenExecutable");
        this.mavenArgs = List.copyOf(mavenArgs);
        this.javaHome = javaHome;
    }

    public MavenRunResult run(Path modulePath, List<String> goals, Duration timeout) {
        List<String> command = new ArrayList<>();
        command.add(resolveExecutable());
        command.addAll(mavenArgs);
        command.addAll(goals);

        Map<String, String> environment = JavaHomeEnvironment.overridesFor(javaHome);

        ProcessResult result;
        try {
            result = processRunner.run(command, modulePath, environment, null, timeout);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MavenRunInterruptedException(modulePath, goals, e);
        }

        List<com.devmanchego.jtestforge.model.CompilerError> compilerErrors =
                CompilerErrorParser.parse(result.stdout() + "\n" + result.stderr());
        return new MavenRunResult(result.exitCode(), result.timedOut(), result.stdout(),
                result.stderr(), compilerErrors);
    }

    /**
     * Resolves a bare command name like {@code "mvn"} to an actually-launchable path.
     * {@link ProcessBuilder} does not search {@code PATHEXT} extensions for a bare name
     * the way a shell does; see {@link ExecutableResolver} for the full reasoning (the
     * same problem {@code MavenClasspathResolver} solves the same way).
     */
    private String resolveExecutable() {
        return ExecutableResolver.resolve(mavenExecutable, ExecutableResolver.systemPathDirectories())
                .map(Path::toString)
                .orElse(mavenExecutable);
    }

    /** Thrown when the calling thread is interrupted while waiting on the Maven process. */
    public static final class MavenRunInterruptedException extends RuntimeException {
        public MavenRunInterruptedException(Path modulePath, List<String> goals, InterruptedException cause) {
            super("Interrupted while running mvn " + String.join(" ", goals) + " in " + modulePath, cause);
        }
    }
}
