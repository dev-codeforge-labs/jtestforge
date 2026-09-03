package com.devmanchego.jtestforge.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ProcessRunnerTest {

    private final ProcessRunner runner = new ProcessRunner();
    private static final boolean WINDOWS = System.getProperty("os.name", "")
            .toLowerCase(java.util.Locale.ROOT).contains("win");

    @Test
    void exceedingTheTimeoutKillsTheProcessAndReportsTimedOut(@TempDir Path workingDirectory)
            throws InterruptedException {
        List<String> command = sleepCommand(10);

        ProcessResult result = runner.run(
                command, workingDirectory, Map.of(), null, Duration.ofMillis(200));

        assertThat(result.timedOut()).isTrue();
        assertThat(result.exitCode()).isEqualTo(ProcessResult.TIMED_OUT_EXIT_CODE);
        assertThat(result.succeeded()).isFalse();
    }

    @Test
    void aProcessThatWritesMoreThanThePipeBufferDoesNotDeadlock(@TempDir Path workingDirectory)
            throws InterruptedException {
        // Regression test: without concurrent draining of both streams, a process that
        // writes enough to fill the OS pipe buffer (a few tens of KB) blocks forever
        // once the parent is only reading one stream (or none) while waiting.
        List<String> command = largeOutputCommand(500_000);

        ProcessResult result = runner.run(
                command, workingDirectory, Map.of(), null, Duration.ofSeconds(30));

        assertThat(result.timedOut()).isFalse();
        assertThat(result.exitCode()).isEqualTo(0);
        assertThat(result.stdout().length()).isGreaterThanOrEqualTo(500_000);
    }

    @Test
    void aNonZeroExitCodeIsReportedWithoutBeingTreatedAsATimeout(@TempDir Path workingDirectory)
            throws InterruptedException {
        List<String> command = exitWithCodeCommand(3);

        ProcessResult result = runner.run(
                command, workingDirectory, Map.of(), null, Duration.ofSeconds(10));

        assertThat(result.timedOut()).isFalse();
        assertThat(result.exitCode()).isEqualTo(3);
        assertThat(result.succeeded()).isFalse();
    }

    @Test
    void stdinContentIsDeliveredToTheProcess(@TempDir Path workingDirectory)
            throws InterruptedException {
        List<String> command = catStdinCommand();

        ProcessResult result = runner.run(
                command, workingDirectory, Map.of(), "hello from jtestforge", Duration.ofSeconds(10));

        assertThat(result.timedOut()).isFalse();
        assertThat(result.stdout()).contains("hello from jtestforge");
    }

    // --- Small cross-platform command builders -----------------------------------
    // Kept local to this test rather than pulled from MavenRunner fixtures (phase 5+):
    // ProcessRunner must be provably correct standing entirely on its own.

    private static List<String> sleepCommand(int seconds) {
        // cmd.exe's `timeout` refuses to run at all under redirected stdin (the case
        // here, since ProcessBuilder always redirects), so it is not usable as a
        // reliable "hangs until killed" fixture on Windows.
        return WINDOWS
                ? List.of("powershell.exe", "-NoProfile", "-NonInteractive", "-Command",
                        "Start-Sleep -Seconds " + seconds)
                : List.of("sh", "-c", "sleep " + seconds);
    }

    private static List<String> largeOutputCommand(int charCount) {
        if (WINDOWS) {
            String powershell = "[Console]::Out.Write(('A' * " + charCount + "))";
            return List.of("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", powershell);
        }
        return List.of("sh", "-c", "yes A | head -c " + charCount);
    }

    private static List<String> exitWithCodeCommand(int code) {
        return WINDOWS
                ? List.of("cmd.exe", "/c", "exit", String.valueOf(code))
                : List.of("sh", "-c", "exit " + code);
    }

    private static List<String> catStdinCommand() {
        return WINDOWS
                ? List.of("powershell.exe", "-NoProfile", "-NonInteractive", "-Command",
                        "[Console]::Out.Write([Console]::In.ReadToEnd())")
                : List.of("cat");
    }
}
