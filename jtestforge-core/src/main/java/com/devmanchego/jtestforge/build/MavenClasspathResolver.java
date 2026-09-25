package com.devmanchego.jtestforge.build;

import com.devmanchego.jtestforge.util.ExecutableResolver;
import com.devmanchego.jtestforge.util.JavaHomeEnvironment;
import com.devmanchego.jtestforge.util.ProcessRunner;
import com.devmanchego.jtestforge.util.ProcessResult;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Resolves a module's compile classpath via {@code mvn dependency:build-classpath} —
 * jtestforge-specification.md §7.1, feeding {@code ProductionTypeSolvers}.
 *
 * <p>Output is written to a temp file (via {@code -Dmdep.outputFile}) rather than parsed
 * from stdout: Maven interleaves {@code [INFO]} lines with the actual classpath on
 * stdout, and the output file contains exactly the classpath string and nothing else.
 */
public final class MavenClasspathResolver {

    private final ProcessRunner processRunner;
    private final String mavenExecutable;
    private final List<String> mavenArgs;
    private final Path javaHome;

    public MavenClasspathResolver(ProcessRunner processRunner, String mavenExecutable, List<String> mavenArgs) {
        this(processRunner, mavenExecutable, mavenArgs, null);
    }

    /** @param javaHome exported to Maven via {@link JavaHomeEnvironment} when not null, as {@link MavenRunner} does */
    public MavenClasspathResolver(ProcessRunner processRunner, String mavenExecutable, List<String> mavenArgs,
                                  Path javaHome) {
        this.processRunner = Objects.requireNonNull(processRunner, "processRunner");
        this.mavenExecutable = Objects.requireNonNull(mavenExecutable, "mavenExecutable");
        this.mavenArgs = List.copyOf(mavenArgs);
        this.javaHome = javaHome;
    }

    /**
     * @param modulePath the Maven module to resolve, per §2 required to already build green
     * @return every classpath entry (jars and directories) as reported by Maven, in the
     *         order Maven produced them
     */
    public List<Path> resolveCompileClasspath(Path modulePath, Duration timeout) {
        Path outputFile;
        try {
            outputFile = Files.createTempFile("jtestforge-classpath-", ".txt");
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to create a temp file for the classpath output", e);
        }

        try {
            List<String> command = buildCommand(outputFile);
            ProcessResult result = processRunner.run(
                    command, modulePath, JavaHomeEnvironment.overridesFor(javaHome), null, timeout);

            if (result.timedOut()) {
                throw new MavenClasspathResolutionException(
                        "mvn dependency:build-classpath timed out after " + timeout + " in " + modulePath);
            }
            if (!result.succeeded()) {
                throw new MavenClasspathResolutionException(
                        "mvn dependency:build-classpath failed in " + modulePath + " (exit "
                                + result.exitCode() + "):\n" + result.stdout() + result.stderr());
            }
            return parseClasspath(outputFile);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MavenClasspathResolutionException(
                    "Interrupted while resolving the classpath for " + modulePath, e);
        } finally {
            try {
                Files.deleteIfExists(outputFile);
            } catch (IOException ignored) {
                // Best-effort cleanup of a temp file; leaving one behind is harmless.
            }
        }
    }

    private List<String> buildCommand(Path outputFile) {
        List<String> command = new ArrayList<>();
        command.add(resolveMavenExecutable());
        command.addAll(mavenArgs);
        command.add("dependency:build-classpath");
        command.add("-Dmdep.outputFile=" + outputFile);
        command.add("-q");
        return command;
    }

    /**
     * Resolves a bare command name like {@code "mvn"} to an actually-launchable path.
     * {@link ProcessBuilder} does not search {@code PATHEXT} extensions for a bare name
     * the way a shell does, so on Windows {@code "mvn"} alone fails to start even when
     * {@code mvn.cmd} is genuinely on {@code PATH}. Falls back to the configured value
     * unchanged if it cannot be resolved, so the resulting process-launch failure carries
     * the original, user-recognisable command rather than a resolution artifact.
     */
    private String resolveMavenExecutable() {
        return ExecutableResolver.resolve(mavenExecutable, ExecutableResolver.systemPathDirectories())
                .map(Path::toString)
                .orElse(mavenExecutable);
    }

    private List<Path> parseClasspath(Path outputFile) {
        String content;
        try {
            content = Files.readString(outputFile).strip();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read classpath output file " + outputFile, e);
        }
        if (content.isEmpty()) {
            return List.of();
        }
        return Arrays.stream(content.split(Pattern.quote(File.pathSeparator)))
                .filter(entry -> !entry.isBlank())
                .map(Path::of)
                .toList();
    }
}
