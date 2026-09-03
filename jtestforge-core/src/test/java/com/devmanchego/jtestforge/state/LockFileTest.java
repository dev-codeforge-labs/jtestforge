package com.devmanchego.jtestforge.state;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LockFileTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-08-27T10:12:44Z"), ZoneOffset.UTC);

    @Test
    void acquiringOnAnUnlockedModuleSucceedsAndCreatesTheLockFile(@TempDir Path stateDir) {
        try (LockFile lock = LockFile.acquire(stateDir, clock)) {
            assertThat(lock.lockPath()).exists();
            assertThat(lock.lockPath().getFileName().toString()).isEqualTo(LockFile.LOCK_FILE_NAME);
        }
    }

    @Test
    void twoProcessesCannotHoldTheLockAtOnce(@TempDir Path stateDir) {
        try (LockFile ignored = LockFile.acquire(stateDir, clock)) {
            assertThatThrownBy(() -> LockFile.acquire(stateDir, clock))
                    .isInstanceOf(LockHeldException.class)
                    .satisfies(thrown -> {
                        LockHeldException e = (LockHeldException) thrown;
                        // The holder is this very JVM, so it is alive: not stale, and
                        // --force-unlock must NOT be suggested.
                        assertThat(e.isStale()).isFalse();
                        assertThat(e.holderPid()).isEqualTo(ProcessHandle.current().pid());
                    });
        }
    }

    @Test
    void releasingTheLockAllowsASubsequentAcquisition(@TempDir Path stateDir) {
        LockFile first = LockFile.acquire(stateDir, clock);
        first.release();

        try (LockFile second = LockFile.acquire(stateDir, clock)) {
            assertThat(second.lockPath()).exists();
        }
    }

    @Test
    void releasingTwiceIsHarmless(@TempDir Path stateDir) {
        LockFile lock = LockFile.acquire(stateDir, clock);

        lock.release();
        lock.release();

        assertThat(lock.lockPath()).doesNotExist();
    }

    @Test
    void aLockLeftByADeadProcessIsReportedAsStaleButStillBlocks(@TempDir Path stateDir) throws Exception {
        long deadPid = pidOfAProcessThatHasExited();
        Files.createDirectories(stateDir);
        Files.writeString(stateDir.resolve(LockFile.LOCK_FILE_NAME), """
                {"pid": %d, "acquiredAt": "2026-08-27T09:00:00Z"}
                """.formatted(deadPid));

        assertThatThrownBy(() -> LockFile.acquire(stateDir, clock))
                .isInstanceOf(LockHeldException.class)
                .satisfies(thrown -> {
                    LockHeldException e = (LockHeldException) thrown;
                    // Stale locks are reported, never cleared automatically: on a machine
                    // that recycles PIDs, auto-clearing defeats the lock exactly when it
                    // matters most.
                    assertThat(e.isStale()).isTrue();
                    assertThat(e.holderPid()).isEqualTo(deadPid);
                })
                .hasMessageContaining("--force-unlock");
    }

    @Test
    void anUnreadableLockFileBlocksRatherThanBeingIgnored(@TempDir Path stateDir) throws IOException {
        Files.createDirectories(stateDir);
        Files.writeString(stateDir.resolve(LockFile.LOCK_FILE_NAME), "this is not json");

        assertThatThrownBy(() -> LockFile.acquire(stateDir, clock))
                .isInstanceOf(LockHeldException.class);
    }

    @Test
    void forceUnlockRemovesTheLockAndReportsWhetherThereWasOneToRemove(@TempDir Path stateDir) {
        LockFile.acquire(stateDir, clock);

        assertThat(LockFile.forceUnlock(stateDir)).isTrue();
        assertThat(stateDir.resolve(LockFile.LOCK_FILE_NAME)).doesNotExist();
        assertThat(LockFile.forceUnlock(stateDir)).isFalse();
    }

    /**
     * Starts and waits for a trivial process, then returns its PID. Reliable across
     * platforms, and genuinely dead by the time it is used - unlike a guessed high PID,
     * which could belong to a live process and make this test flaky.
     */
    private long pidOfAProcessThatHasExited() throws IOException, InterruptedException {
        Process process = new ProcessBuilder(javaExecutable(), "--version")
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        long pid = process.pid();
        process.waitFor();
        return pid;
    }

    private String javaExecutable() {
        String suffix = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT)
                .contains("win") ? ".exe" : "";
        return Path.of(System.getProperty("java.home"), "bin", "java" + suffix).toString();
    }
}
