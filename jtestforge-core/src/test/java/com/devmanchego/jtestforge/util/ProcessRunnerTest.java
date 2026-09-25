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

    /**
     * The one chokepoint every external process goes through (Maven, the AI CLI, PIT) is
     * exactly where {@code -v/--verbose} needs to hook in to echo every command JTestForge
     * runs, without every caller repeating that wiring itself.
     */
    @Test
    void aCommandSinkIsToldTheFullCommandBeforeLaunchAndTheExitCodeAfter(@TempDir Path workingDirectory)
            throws InterruptedException {
        List<String> echoed = new java.util.ArrayList<>();
        ProcessRunner sinkedRunner = new ProcessRunner(java.nio.charset.Charset.defaultCharset(), echoed::add);
        List<String> command = exitWithCodeCommand(3);

        sinkedRunner.run(command, workingDirectory, Map.of(), null, Duration.ofSeconds(10));

        assertThat(echoed).hasSize(2);
        assertThat(echoed.get(0)).startsWith("$ ").contains(command.get(0)).contains(workingDirectory.toString());
        assertThat(echoed.get(1)).contains("exit 3");
    }

    @Test
    void aTimedOutCommandIsReportedToTheSinkAsSuch(@TempDir Path workingDirectory) throws InterruptedException {
        List<String> echoed = new java.util.ArrayList<>();
        ProcessRunner sinkedRunner = new ProcessRunner(java.nio.charset.Charset.defaultCharset(), echoed::add);

        sinkedRunner.run(sleepCommand(10), workingDirectory, Map.of(), null, Duration.ofMillis(200));

        assertThat(echoed.get(1)).contains("timed out");
    }

    /**
     * The whole point of echoing this: telling "JTestForge sent the wrong JAVA_HOME" apart
     * from "the value it sent was ignored downstream" needs seeing what was actually sent,
     * not just the bare command line.
     */
    @Test
    void environmentOverridesAreEchoedAlongsideTheCommand(@TempDir Path workingDirectory)
            throws InterruptedException {
        List<String> echoed = new java.util.ArrayList<>();
        ProcessRunner sinkedRunner = new ProcessRunner(java.nio.charset.Charset.defaultCharset(), echoed::add);

        sinkedRunner.run(exitWithCodeCommand(0), workingDirectory,
                Map.of("JAVA_HOME", "C:\\tools\\java\\openjdk8-temurin"), null, Duration.ofSeconds(10));

        assertThat(echoed.get(0)).contains("[env: JAVA_HOME=C:\\tools\\java\\openjdk8-temurin]");
    }

    @Test
    void anEmptyEnvironmentOverrideAddsNothingToTheEcho(@TempDir Path workingDirectory) throws InterruptedException {
        List<String> echoed = new java.util.ArrayList<>();
        ProcessRunner sinkedRunner = new ProcessRunner(java.nio.charset.Charset.defaultCharset(), echoed::add);

        sinkedRunner.run(exitWithCodeCommand(0), workingDirectory, Map.of(), null, Duration.ofSeconds(10));

        assertThat(echoed.get(0)).doesNotContain("[env:");
    }

    @Test
    void withoutACommandSinkNothingIsEchoedAnywhere(@TempDir Path workingDirectory) throws InterruptedException {
        // Passing simply confirms run() tolerates the default (null) sink.
        runner.run(exitWithCodeCommand(0), workingDirectory, Map.of(), null, Duration.ofSeconds(10));
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

    /**
     * A killed process's partial output used to be discarded wholesale, which is exactly
     * the output needed to tell WHY it had to be killed (a CLI's own retry/backoff chatter,
     * a proxy error) from the outside.
     */
    @Test
    void outputPrintedBeforeATimeoutKillIsStillReturned(@TempDir Path workingDirectory)
            throws InterruptedException {
        ProcessResult result = runner.run(printThenSleepCommand("still-here-after-the-kill", 10),
                workingDirectory, Map.of(), null, Duration.ofSeconds(3));

        assertThat(result.timedOut()).isTrue();
        assertThat(result.stdout()).contains("still-here-after-the-kill");
    }

    @Test
    void aTimingSinkReportsTheWallClockBreakdownWithoutAnyOutputContent(@TempDir Path workingDirectory)
            throws InterruptedException {
        List<String> timings = new java.util.ArrayList<>();
        ProcessRunner timedRunner = new ProcessRunner(
                java.nio.charset.Charset.defaultCharset(), null, timings::add);

        timedRunner.run(catStdinCommand(), workingDirectory, Map.of(),
                "secret prompt content", Duration.ofSeconds(30));

        assertThat(timings).singleElement().asString()
                .contains("[timing]").contains("launch").contains("1st stdout").contains("exit 0")
                .contains("stdin 21 chars")
                .doesNotContain("secret prompt content");
    }

    @Test
    void aTimedOutProcessIsReportedAsSuchToTheTimingSink(@TempDir Path workingDirectory)
            throws InterruptedException {
        List<String> timings = new java.util.ArrayList<>();
        ProcessRunner timedRunner = new ProcessRunner(
                java.nio.charset.Charset.defaultCharset(), null, timings::add);

        timedRunner.run(sleepCommand(10), workingDirectory, Map.of(), null, Duration.ofMillis(300));

        assertThat(timings).singleElement().asString().contains("TIMED OUT");
    }

    /**
     * The regression this whole tree-kill exists for: a wrapper script that spawns a
     * longer-lived grandchild and exits. Killing only the direct child leaves the
     * grandchild holding the inherited pipe, so the drains never reach EOF and the call
     * hangs indefinitely - an hour past a five-minute timeout, in the run that prompted it.
     */
    @Test
    void aGrandchildOutlivingItsParentDoesNotHangTheCall(@TempDir Path workingDirectory)
            throws InterruptedException {
        long startedAt = System.currentTimeMillis();

        ProcessResult result = runner.run(spawnDetachedChildCommand(30), workingDirectory,
                Map.of(), null, Duration.ofSeconds(3));

        long elapsed = System.currentTimeMillis() - startedAt;
        assertThat(result).isNotNull();
        // Comfortably under the 30s the grandchild would otherwise keep the pipe open for.
        assertThat(elapsed).isLessThan(20_000L);
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

    private static List<String> printThenSleepCommand(String text, int seconds) {
        return WINDOWS
                ? List.of("powershell.exe", "-NoProfile", "-NonInteractive", "-Command",
                        "[Console]::Out.WriteLine('" + text + "'); Start-Sleep -Seconds " + seconds)
                : List.of("sh", "-c", "echo " + text + "; sleep " + seconds);
    }

    /**
     * A parent that starts a longer-lived child and exits immediately, mirroring a
     * {@code .cmd} wrapper handing off to {@code node.exe}. The grandchild inherits the
     * stdout pipe, so only killing the tree lets the drain reach end-of-stream.
     */
    private static List<String> spawnDetachedChildCommand(int childSeconds) {
        return WINDOWS
                ? List.of("cmd.exe", "/c",
                        "start /b powershell.exe -NoProfile -NonInteractive -Command Start-Sleep -Seconds "
                                + childSeconds)
                : List.of("sh", "-c", "sleep " + childSeconds + " & exit 0");
    }

    private static List<String> catStdinCommand() {
        return WINDOWS
                ? List.of("powershell.exe", "-NoProfile", "-NonInteractive", "-Command",
                        "[Console]::Out.Write([Console]::In.ReadToEnd())")
                : List.of("cat");
    }
}
