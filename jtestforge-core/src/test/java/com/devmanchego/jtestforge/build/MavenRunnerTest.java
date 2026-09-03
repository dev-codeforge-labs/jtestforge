package com.devmanchego.jtestforge.build;

import com.devmanchego.jtestforge.util.ExecutableResolver;
import com.devmanchego.jtestforge.util.ProcessRunner;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real, offline Maven invocations against jtestforge-core's own module directory -
 * self-referential, like {@code MavenClasspathResolverTest}, and possible without a
 * network since this project's own dependencies are already resolved locally by the
 * time this test runs.
 */
class MavenRunnerTest {

    private static final Duration TIMEOUT = Duration.ofMinutes(2);

    private Path ownModuleDirectory;

    @BeforeEach
    void locateOwnModuleAndRequireMaven() {
        ownModuleDirectory = Path.of("").toAbsolutePath();
        Assumptions.assumeTrue(Files.isRegularFile(ownModuleDirectory.resolve("pom.xml")),
                "This test must run with jtestforge-core as the working directory.");
        Assumptions.assumeTrue(
                ExecutableResolver.isResolvable("mvn", ExecutableResolver.systemPathDirectories()),
                "mvn is not on PATH in this environment.");
    }

    @Test
    void aRealOfflineInvocationSucceedsAndCapturesNoCompilerErrors() {
        // "validate" touches no compiler and no network - it proves the process wiring
        // (executable resolution, argument order, working directory) without needing the
        // module's dependencies to already be resolved.
        MavenRunner runner = new MavenRunner(new ProcessRunner(), "mvn", List.of("-o", "-B"));

        MavenRunResult result = runner.run(ownModuleDirectory, List.of("validate"), TIMEOUT);

        assertThat(result.succeeded()).isTrue();
        assertThat(result.compilerErrors()).isEmpty();
    }

    @Test
    void anUnknownGoalFailsWithANonZeroExitAndNoTimeout() {
        MavenRunner runner = new MavenRunner(new ProcessRunner(), "mvn", List.of("-o", "-B"));

        MavenRunResult result = runner.run(ownModuleDirectory,
                List.of("this-goal-does-not-exist:whatsoever"), TIMEOUT);

        assertThat(result.succeeded()).isFalse();
        assertThat(result.timedOut()).isFalse();
        assertThat(result.exitCode()).isNotZero();
    }

    @Test
    void aBareCommandNameResolvesOnWindowsEvenWhenAPosixScriptSharesTheName() {
        // Regression: mvn's own distribution ships both a POSIX "mvn" script and
        // "mvn.cmd" side by side. Resolving to the wrong one fails immediately with
        // "%1 is not a valid Win32 application" - this proves the real resolution path,
        // not just ExecutableResolverTest's synthetic fixtures.
        MavenRunner runner = new MavenRunner(new ProcessRunner(), "mvn", List.of("-o", "-B"));

        MavenRunResult result = runner.run(ownModuleDirectory, List.of("validate"), TIMEOUT);

        assertThat(result.succeeded()).isTrue();
    }

    @Test
    void javaHomeIsPassedThroughToTheChildProcessWhenConfigured() {
        String currentJavaHome = System.getProperty("java.home");
        MavenRunner runner = new MavenRunner(
                new ProcessRunner(), "mvn", List.of("-o", "-B"), Path.of(currentJavaHome));

        MavenRunResult result = runner.run(ownModuleDirectory, List.of("validate"), TIMEOUT);

        assertThat(result.succeeded()).isTrue();
    }
}
