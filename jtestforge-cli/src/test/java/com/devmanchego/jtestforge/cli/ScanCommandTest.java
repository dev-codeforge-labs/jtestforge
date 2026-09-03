package com.devmanchego.jtestforge.cli;

import com.devmanchego.jtestforge.util.ExecutableResolver;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code jtestforge scan} — jtestforge-implementation-plan.md phase 17.
 *
 * <p>Real, offline against jtestforge-core's own module directory, the same
 * self-referential pattern {@code MavenClasspathResolverTest}/{@code MavenRunnerTest}
 * already use: a real module, already resolvable without a network, with production
 * classes and Spring/JPA annotations genuinely worth classifying.
 */
class ScanCommandTest {

    private Path ownCoreModule;

    @BeforeEach
    void locateCoreModuleAndRequireMaven() {
        ownCoreModule = Path.of("..", "jtestforge-core").toAbsolutePath().normalize();
        Assumptions.assumeTrue(Files.isRegularFile(ownCoreModule.resolve("pom.xml")),
                "This test must run with jtestforge-cli as the working directory.");
        Assumptions.assumeTrue(
                ExecutableResolver.isResolvable("mvn", ExecutableResolver.systemPathDirectories()),
                "mvn is not on PATH in this environment.");
    }

    @Test
    void scanningJtestforgeCoreItselfPrintsATierBreakdownAndNoWritesHappen() throws IOException {
        CommandOutcome outcome = run(ownCoreModule);

        assertThat(outcome.exitCode).isEqualTo(0);
        assertThat(outcome.out).contains("production class(es) under src/main/java");
        assertThat(outcome.out).contains("Tier").contains("Methods").contains("PLAIN_UNIT");
        assertThat(outcome.out).contains("framework-semantic gap(s) currently open");

        // No AI call, no state directory, no write of any kind (§14: "the cheap dry run").
        assertThat(Files.exists(ownCoreModule.resolve(".jtestforge"))).isFalse();
    }

    @Test
    void aModuleWithNoPomIsRejectedBeforeAnyMavenInvocation(@TempDir Path dir) throws IOException {
        writeMinimalConfig(dir);

        CommandOutcome outcome = run(dir);

        assertThat(outcome.exitCode).isEqualTo(1);
        assertThat(outcome.err).contains("pom.xml");
    }

    private void writeMinimalConfig(Path dir) throws IOException {
        Files.writeString(dir.resolve("jtestforge.yaml"), "project:\n  modulePath: .\n", StandardCharsets.UTF_8);
    }

    private CommandOutcome run(Path moduleDir) throws IOException {
        Path config = moduleDir.resolve("jtestforge.yaml");
        boolean configAlreadyExisted = Files.exists(config);
        if (!configAlreadyExisted) {
            writeMinimalConfig(moduleDir);
        }
        try {
            CommandLine commandLine = new CommandLine(new ScanCommand());
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ByteArrayOutputStream err = new ByteArrayOutputStream();
            commandLine.setOut(new PrintWriter(out, true, StandardCharsets.UTF_8));
            commandLine.setErr(new PrintWriter(err, true, StandardCharsets.UTF_8));
            int exitCode = commandLine.execute("--module", moduleDir.toString());
            return new CommandOutcome(exitCode, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
        } finally {
            if (!configAlreadyExisted) {
                Files.deleteIfExists(config);
            }
        }
    }

    private record CommandOutcome(int exitCode, String out, String err) {
    }
}
