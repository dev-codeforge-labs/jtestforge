package com.devmanchego.jtestforge.provider;

import com.devmanchego.jtestforge.config.PromptDelivery;
import com.devmanchego.jtestforge.util.ProcessRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Real process invocations, not mocked - consistent with {@code ProcessRunnerTest} and
 * every other phase that has touched external processes so far.
 *
 * <p>Every fixture is a real script FILE (a {@code .ps1} on Windows, a POSIX shell script
 * elsewhere), invoked via {@code -File}/direct interpreter call. That specifically avoids
 * relying on how {@code powershell -Command "..."} binds trailing command-line tokens to
 * {@code $args} - a genuinely version-sensitive, shell-quoting-adjacent behaviour this
 * test suite has no reason to depend on, when a script file's argument binding is
 * completely unambiguous.
 */
class ProcessAiProviderTest {

    private static final boolean WINDOWS = System.getProperty("os.name", "")
            .toLowerCase(Locale.ROOT).contains("win");
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    @Test
    void stdinDeliveryActuallySendsThePromptOnStandardInput(@TempDir Path dir) throws Exception {
        Script script = writeScript(dir, "echo-stdin",
                "[Console]::Out.Write([Console]::In.ReadToEnd())",
                "cat");
        ProcessAiProvider provider = provider(script, PromptDelivery.STDIN, 0);

        AiResponse response = provider.invoke("hello from jtestforge", TIMEOUT);

        assertThat(response.content()).contains("hello from jtestforge");
    }

    @Test
    void argumentDeliveryAppendsThePromptAsATrailingArgument(@TempDir Path dir) throws Exception {
        Script script = writeScript(dir, "echo-arg",
                "[Console]::Out.Write($args[0])",
                "printf '%s' \"$1\"");
        ProcessAiProvider provider = provider(script, PromptDelivery.ARGUMENT, 0);

        AiResponse response = provider.invoke("the prompt text", TIMEOUT);

        assertThat(response.content()).contains("the prompt text");
    }

    @Test
    void fileDeliveryWritesThePromptToATempFileAndAppendsItsPath(@TempDir Path dir) throws Exception {
        Script script = writeScript(dir, "cat-file",
                "[Console]::Out.Write([IO.File]::ReadAllText($args[0]))",
                "cat \"$1\"");
        ProcessAiProvider provider = provider(script, PromptDelivery.FILE, 0);

        AiResponse response = provider.invoke("prompt written to a file", TIMEOUT);

        assertThat(response.content()).contains("prompt written to a file");
    }

    @Test
    void theFileDeliveryTempFileIsRemovedAfterTheInvocation(@TempDir Path dir) throws Exception {
        // The prompt file is JTestForge's own temp file, not something to leave behind
        // for every generation attempt - same discipline as MavenClasspathResolver's
        // output file (phase 3).
        Script script = writeScript(dir, "capture-path",
                "[IO.File]::WriteAllText(\"" + dir.resolve("captured-path.txt") + "\", $args[0])\n"
                        + "Write-Output \"captured\"",
                "printf '%s' \"$1\" > \"" + dir.resolve("captured-path.txt") + "\"\necho captured");
        ProcessAiProvider provider = provider(script, PromptDelivery.FILE, 0);

        provider.invoke("prompt", TIMEOUT);

        String capturedPath = Files.readString(dir.resolve("captured-path.txt")).strip();
        assertThat(Path.of(capturedPath)).doesNotExist();
    }

    @Test
    void theProviderIdIsReportedAsConfigured(@TempDir Path dir) throws Exception {
        Script script = writeScript(dir, "noop", "", ":");
        ProcessAiProvider provider = new ProcessAiProvider("claude", new ProcessRunner(),
                script.command(), script.args(), PromptDelivery.STDIN, 0);

        assertThat(provider.id()).isEqualTo("claude");
    }

    @Test
    void aNonZeroExitIsRetriedAndEventuallySucceeds(@TempDir Path dir) throws Exception {
        Path counterFile = dir.resolve("attempts.txt");
        Script script = writeScript(dir, "succeed-on-third",
                psSucceedOnThirdAttempt(counterFile),
                shSucceedOnThirdAttempt(counterFile));
        ProcessAiProvider provider = provider(script, PromptDelivery.STDIN, 3);

        AiResponse response = provider.invoke("prompt", TIMEOUT);

        assertThat(response.content()).contains("success");
        assertThat(Files.readString(counterFile).strip()).isEqualTo("3");
    }

    @Test
    void exhaustingEveryRetryThrowsProviderException(@TempDir Path dir) throws Exception {
        Script script = writeScript(dir, "always-fail", "exit 1", "exit 1");
        ProcessAiProvider provider = provider(script, PromptDelivery.STDIN, 2);

        assertThatThrownBy(() -> provider.invoke("prompt", TIMEOUT))
                .isInstanceOf(ProviderException.class);
    }

    @Test
    void emptyStdoutCountsAsATransportFailureNotAsAWellFormedEmptyAnswer(@TempDir Path dir) throws Exception {
        // A well-formed-but-useless answer is the response parser's problem (§12.2); an
        // EMPTY answer means the CLI itself did not really respond, which is a transport
        // concern this provider must retry rather than pass through as success.
        Script script = writeScript(dir, "empty-output", "exit 0", "exit 0");
        ProcessAiProvider provider = provider(script, PromptDelivery.STDIN, 0);

        assertThatThrownBy(() -> provider.invoke("prompt", TIMEOUT))
                .isInstanceOf(ProviderException.class);
    }

    @Test
    void aTimeoutIsATransportFailureThatCanBeRetried(@TempDir Path dir) throws Exception {
        Path counterFile = dir.resolve("attempts.txt");
        Script script = writeScript(dir, "slow-then-fast",
                psSlowOnFirstAttempt(counterFile),
                shSlowOnFirstAttempt(counterFile));
        ProcessAiProvider provider = new ProcessAiProvider("test", new ProcessRunner(),
                script.command(), script.args(), PromptDelivery.STDIN, 1);

        AiResponse response = provider.invoke("prompt", Duration.ofMillis(500));

        assertThat(response.content()).contains("fast");
    }

    // --- fixtures --------------------------------------------------------------------

    private ProcessAiProvider provider(Script script, PromptDelivery delivery, int transportRetries) {
        return new ProcessAiProvider("test", new ProcessRunner(),
                script.command(), script.args(), delivery, transportRetries);
    }

    private String psSucceedOnThirdAttempt(Path counterFile) {
        // [IO.File]::WriteAllText/ReadAllText default to UTF-8 without a BOM, matching
        // what Files.readString expects. Out-File's default PowerShell 5.1 encoding is
        // UTF-16LE with a BOM, which Files.readString cannot decode.
        return """
                $count = 0
                if (Test-Path "%s") { $count = [int]([IO.File]::ReadAllText("%s")) }
                $count++
                [IO.File]::WriteAllText("%s", [string]$count)
                if ($count -lt 3) { exit 1 } else { Write-Output "success"; exit 0 }
                """.formatted(counterFile, counterFile, counterFile);
    }

    private String shSucceedOnThirdAttempt(Path counterFile) {
        return """
                count=0
                if [ -f "%s" ]; then count=$(cat "%s"); fi
                count=$((count + 1))
                printf '%%s' "$count" > "%s"
                if [ "$count" -lt 3 ]; then exit 1; else echo success; exit 0; fi
                """.formatted(counterFile, counterFile, counterFile);
    }

    private String psSlowOnFirstAttempt(Path counterFile) {
        return """
                if (-not (Test-Path "%s")) {
                    [IO.File]::WriteAllText("%s", "1")
                    Start-Sleep -Seconds 5
                    Write-Output "slow"
                } else {
                    Write-Output "fast"
                }
                """.formatted(counterFile, counterFile);
    }

    private String shSlowOnFirstAttempt(Path counterFile) {
        return """
                if [ ! -f "%s" ]; then
                    printf '1' > "%s"
                    sleep 5
                    echo slow
                else
                    echo fast
                fi
                """.formatted(counterFile, counterFile);
    }

    private Script writeScript(Path dir, String baseName, String powershellBody, String posixBody) throws IOException {
        if (WINDOWS) {
            Path script = dir.resolve(baseName + ".ps1");
            Files.writeString(script, powershellBody);
            // -ExecutionPolicy Bypass is scoped to this one process invocation only - it
            // changes nothing persistent about the machine. Without it, PowerShell's
            // default Restricted policy on a fresh Windows install refuses to run any
            // .ps1 file at all, regardless of its content.
            return new Script("powershell", List.of("-NoProfile", "-NonInteractive",
                    "-ExecutionPolicy", "Bypass", "-File", script.toString()));
        }
        Path script = dir.resolve(baseName + ".sh");
        Files.writeString(script, "#!/bin/sh\n" + posixBody);
        script.toFile().setExecutable(true);
        return new Script("sh", List.of(script.toString()));
    }

    private record Script(String command, List<String> args) {
    }
}
