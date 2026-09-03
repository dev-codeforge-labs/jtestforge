package com.devmanchego.jtestforge.cli;

import com.devmanchego.jtestforge.state.LockFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;

/** {@code jtestforge clean} — jtestforge-implementation-plan.md phase 17. */
class CleanCommandTest {

    @Test
    void removesStateTranscriptsAndBackups(@TempDir Path dir) throws IOException {
        writeMinimalConfig(dir);
        Path stateDir = dir.resolve(".jtestforge");
        Files.createDirectories(stateDir.resolve("transcripts").resolve("unit-1"));
        Files.createDirectories(stateDir.resolve("backup"));
        Files.writeString(stateDir.resolve("state.json"), "{}", StandardCharsets.UTF_8);
        Files.writeString(stateDir.resolve("transcripts").resolve("unit-1").resolve("1.prompt.md"), "x");
        Files.writeString(stateDir.resolve("backup").resolve("PaymentServiceTest.java.orig"), "x");

        int exitCode = run(dir);

        assertThat(exitCode).isEqualTo(0);
        assertThat(Files.exists(stateDir)).isFalse();
    }

    @Test
    void aModuleWithNoStateDirYetIsANoOpNotAFailure(@TempDir Path dir) throws IOException {
        writeMinimalConfig(dir);

        int exitCode = run(dir);

        assertThat(exitCode).isEqualTo(0);
    }

    @Test
    void refusesToCleanWhileAnotherRunGenuinelyHoldsTheLock(@TempDir Path dir) throws IOException {
        writeMinimalConfig(dir);
        Path stateDir = dir.resolve(".jtestforge");
        // Acquired by this very test process, so ProcessHandle.isAlive() is true - a
        // faithful stand-in for "another JTestForge run is genuinely active".
        LockFile activeLock = LockFile.acquire(stateDir, Clock.systemUTC());

        try {
            int exitCode = run(dir);

            assertThat(exitCode).isEqualTo(2);
            assertThat(Files.exists(stateDir)).isTrue();
        } finally {
            activeLock.release();
        }
    }

    @Test
    void aStaleLockDoesNotBlockCleaningSinceItIsOneOfTheFilesRemoved(@TempDir Path dir) throws IOException {
        writeMinimalConfig(dir);
        Path stateDir = dir.resolve(".jtestforge");
        Files.createDirectories(stateDir);
        // A PID that (barring extraordinary coincidence) is not a live process on this
        // machine - simulates a lock left behind by a process that has since died.
        Files.writeString(stateDir.resolve("jtestforge.lock"),
                "{\"pid\": 999999, \"acquiredAt\": \"2020-01-01T00:00:00Z\"}", StandardCharsets.UTF_8);

        int exitCode = run(dir);

        assertThat(exitCode).isEqualTo(0);
        assertThat(Files.exists(stateDir)).isFalse();
    }

    private void writeMinimalConfig(Path dir) throws IOException {
        Files.writeString(dir.resolve("jtestforge.yaml"), "project:\n  modulePath: .\n", StandardCharsets.UTF_8);
    }

    private int run(Path dir) {
        return new CommandLine(new CleanCommand()).execute("--module", dir.toString());
    }
}
