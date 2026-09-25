package com.devmanchego.jtestforge.build;

import com.devmanchego.jtestforge.model.ModuleDependency;
import com.devmanchego.jtestforge.util.ExecutableResolver;
import com.devmanchego.jtestforge.util.JavaHomeEnvironment;
import com.devmanchego.jtestforge.util.ProcessResult;
import com.devmanchego.jtestforge.util.ProcessRunner;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Resolves a module's full resolved dependency list via {@code mvn dependency:list} —
 * jtestforge-specification.md §7.3, feeding {@link com.devmanchego.jtestforge.spring.SpringStackDetector}
 * and {@link com.devmanchego.jtestforge.build.TestFrameworkDetector}.
 *
 * <p>Mirrors {@link MavenClasspathResolver}: output goes to a temp file via
 * {@code -DoutputFile}, not stdout, for the same reason - Maven interleaves {@code [INFO]}
 * lines with the listing on stdout, and the output file does not.
 */
public final class ModuleDependencyResolver {

    private final ProcessRunner processRunner;
    private final String mavenExecutable;
    private final List<String> mavenArgs;
    private final Path javaHome;

    public ModuleDependencyResolver(ProcessRunner processRunner, String mavenExecutable, List<String> mavenArgs) {
        this(processRunner, mavenExecutable, mavenArgs, null);
    }

    /** @param javaHome exported to Maven via {@link JavaHomeEnvironment} when not null, as {@link MavenRunner} does */
    public ModuleDependencyResolver(ProcessRunner processRunner, String mavenExecutable, List<String> mavenArgs,
                                    Path javaHome) {
        this.processRunner = Objects.requireNonNull(processRunner, "processRunner");
        this.mavenExecutable = Objects.requireNonNull(mavenExecutable, "mavenExecutable");
        this.mavenArgs = List.copyOf(mavenArgs);
        this.javaHome = javaHome;
    }

    /** @param modulePath the Maven module to resolve, per §2 required to already build green */
    public List<ModuleDependency> resolveDependencies(Path modulePath, Duration timeout) {
        Path outputFile;
        try {
            outputFile = Files.createTempFile("jtestforge-dependencies-", ".txt");
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to create a temp file for the dependency listing", e);
        }

        try {
            List<String> command = buildCommand(outputFile);
            ProcessResult result = processRunner.run(
                    command, modulePath, JavaHomeEnvironment.overridesFor(javaHome), null, timeout);

            if (result.timedOut()) {
                throw new MavenClasspathResolutionException(
                        "mvn dependency:list timed out after " + timeout + " in " + modulePath);
            }
            if (!result.succeeded()) {
                throw new MavenClasspathResolutionException(
                        "mvn dependency:list failed in " + modulePath + " (exit "
                                + result.exitCode() + "):\n" + result.stdout() + result.stderr());
            }
            return readAndParse(outputFile);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MavenClasspathResolutionException(
                    "Interrupted while resolving dependencies for " + modulePath, e);
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
        command.add("dependency:list");
        command.add("-DoutputFile=" + outputFile);
        command.add("-q");
        return command;
    }

    private String resolveMavenExecutable() {
        return ExecutableResolver.resolve(mavenExecutable, ExecutableResolver.systemPathDirectories())
                .map(Path::toString)
                .orElse(mavenExecutable);
    }

    private List<ModuleDependency> readAndParse(Path outputFile) {
        String content;
        try {
            content = Files.readString(outputFile);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read dependency listing file " + outputFile, e);
        }
        return MavenDependencyListParser.parse(content);
    }
}
