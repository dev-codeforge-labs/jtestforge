package com.devmanchego.jtestforge.provider;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * jtestforge-specification.md §12.2: "Every prompt and every raw response is written to
 * {@code <stateDir>/transcripts/<unitId>/<attempt>.{prompt,response}.md}." A cross-cutting
 * concern applied around any {@link AiProvider}, not a responsibility of the SPI itself -
 * see {@link TranscriptWriter}'s own Javadoc for why.
 */
class TranscriptWriterTest {

    @Test
    void writesBothThePromptAndTheResponseOnSuccess(@TempDir Path stateDir) throws Exception {
        TranscriptWriter writer = new TranscriptWriter(stateDir);
        RecordedAiProvider provider = new RecordedAiProvider("claude").enqueueResponse("the response text");

        writer.invokeAndRecord(provider, "the prompt text", Duration.ofSeconds(5), "com.acme.Foo#bar()", 1);

        Path unitDir = stateDir.resolve("transcripts").resolve("com.acme.Foo#bar()");
        assertThat(Files.readString(unitDir.resolve("1.prompt.md"))).isEqualTo("the prompt text");
        assertThat(Files.readString(unitDir.resolve("1.response.md"))).isEqualTo("the response text");
    }

    @Test
    void thePromptIsStillWrittenWhenTheProviderFails(@TempDir Path stateDir) throws Exception {
        // The transcript is the single most valuable artifact for tuning prompts (§12.2)
        // - it must not vanish just because the attempt that used it failed.
        TranscriptWriter writer = new TranscriptWriter(stateDir);
        RecordedAiProvider provider = new RecordedAiProvider("claude").enqueueFailure("transport failure");

        assertThatThrownBy(() -> writer.invokeAndRecord(
                provider, "the prompt that failed", Duration.ofSeconds(5), "com.acme.Foo#bar()", 1))
                .isInstanceOf(ProviderException.class);

        Path promptFile = stateDir.resolve("transcripts").resolve("com.acme.Foo#bar()").resolve("1.prompt.md");
        assertThat(Files.readString(promptFile)).isEqualTo("the prompt that failed");
    }

    /**
     * A failed attempt is exactly the one a user most needs the full transport detail
     * for - a truncated one-line message in state.json is not enough to diagnose it.
     */
    @Test
    void aFailedAttemptGetsAResponseFileWithTheFullDiagnosticsInstead(@TempDir Path stateDir) throws Exception {
        TranscriptWriter writer = new TranscriptWriter(stateDir);
        AiProvider provider = new AiProvider() {
            public String id() {
                return "gemini";
            }

            public AiResponse invoke(String prompt, Duration timeout) throws ProviderException {
                throw new ProviderException("\"gemini\" attempt 3/3 failed: exited with code 55",
                        new ProviderException.Diagnostics(
                                "gemini.cmd -p \"\"", 55, "partial stdout here", "ERROR: sintaxis no valida."));
            }
        };

        assertThatThrownBy(() -> writer.invokeAndRecord(
                provider, "the prompt", Duration.ofSeconds(5), "com.acme.Foo#bar()", 1))
                .isInstanceOf(ProviderException.class);

        String dump = Files.readString(
                stateDir.resolve("transcripts").resolve("com.acme.Foo#bar()").resolve("1.response.md"));
        assertThat(dump).contains("exited with code 55")
                .contains("gemini.cmd -p").contains("Exit code: 55")
                .contains("partial stdout here").contains("ERROR: sintaxis no valida.");
    }

    @Test
    void aVerboseSinkReceivesTheFullPromptAndFailureDiagnosticsWhenGiven(@TempDir Path stateDir) throws Exception {
        List<String> echoed = new ArrayList<>();
        TranscriptWriter writer = new TranscriptWriter(stateDir, echoed::add);
        RecordedAiProvider provider = new RecordedAiProvider("gemini").enqueueFailure("boom");

        assertThatThrownBy(() -> writer.invokeAndRecord(
                provider, "the prompt", Duration.ofSeconds(5), "com.acme.Foo#bar()", 1))
                .isInstanceOf(ProviderException.class);

        assertThat(echoed).hasSize(2);
        assertThat(echoed.get(0)).contains("com.acme.Foo#bar()").contains("-> gemini").contains("the prompt");
        assertThat(echoed.get(1)).contains("<- gemini FAILED").contains("boom");
    }

    @Test
    void aProgressSinkReceivesOneShortContentFreeLinePerRequestAndResponse(@TempDir Path stateDir) throws Exception {
        List<String> progress = new ArrayList<>();
        TranscriptWriter writer = new TranscriptWriter(stateDir, progress::add, null);
        RecordedAiProvider provider = new RecordedAiProvider("claude").enqueueResponse("the response text");

        writer.invokeAndRecord(provider, "the prompt text", Duration.ofSeconds(5), "com.acme.Foo#bar()", 1);

        assertThat(progress).hasSize(2);
        assertThat(progress.get(0)).contains("claude").contains("requesting tests").contains("attempt 1")
                .doesNotContain("the prompt text");
        assertThat(progress.get(1)).contains("claude").contains("received")
                .doesNotContain("the response text");
    }

    @Test
    void aProgressSinkReceivesAFailedLineWithoutLeakingDiagnosticContent(@TempDir Path stateDir) throws Exception {
        List<String> progress = new ArrayList<>();
        TranscriptWriter writer = new TranscriptWriter(stateDir, progress::add, null);
        RecordedAiProvider provider = new RecordedAiProvider("gemini").enqueueFailure("transport failure");

        assertThatThrownBy(() -> writer.invokeAndRecord(
                provider, "secret prompt content", Duration.ofSeconds(5), "com.acme.Foo#bar()", 1))
                .isInstanceOf(ProviderException.class);

        assertThat(progress).hasSize(2);
        assertThat(progress.get(1)).contains("FAILED").doesNotContain("secret prompt content");
    }

    /**
     * A first-time run of a CLI like {@code gemini} can sit on an interactive/browser
     * authentication step with zero other output - the heartbeat is what tells the user
     * the run is still alive rather than hung.
     */
    @Test
    void aStillWaitingLineIsEmittedWhileBlockedOnASlowProvider(@TempDir Path stateDir) throws Exception {
        List<String> progress = new CopyOnWriteArrayList<>();
        TranscriptWriter writer = new TranscriptWriter(stateDir, progress::add, null, Duration.ofMillis(20));
        AiProvider slowProvider = new AiProvider() {
            public String id() {
                return "gemini";
            }

            public AiResponse invoke(String prompt, Duration timeout) {
                try {
                    Thread.sleep(150);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return new AiResponse("the response", 150);
            }
        };

        writer.invokeAndRecord(slowProvider, "the prompt", Duration.ofSeconds(5), "com.acme.Foo#bar()", 1);

        assertThat(progress).anySatisfy(line -> assertThat(line)
                .contains("gemini").contains("still waiting").doesNotContain("the prompt"));
    }

    @Test
    void noHeartbeatLineIsEmittedForAFastProvider(@TempDir Path stateDir) throws Exception {
        List<String> progress = new CopyOnWriteArrayList<>();
        TranscriptWriter writer = new TranscriptWriter(stateDir, progress::add, null, Duration.ofSeconds(20));
        RecordedAiProvider provider = new RecordedAiProvider("claude").enqueueResponse("fast response");

        writer.invokeAndRecord(provider, "the prompt", Duration.ofSeconds(5), "com.acme.Foo#bar()", 1);

        assertThat(progress).noneMatch(line -> line.contains("still waiting"));
    }

    @Test
    void aReceivedLineUsesTheHumanReadableDurationFormat(@TempDir Path stateDir) throws Exception {
        List<String> progress = new ArrayList<>();
        TranscriptWriter writer = new TranscriptWriter(stateDir, progress::add, null);
        AiProvider provider = new AiProvider() {
            public String id() {
                return "claude";
            }

            public AiResponse invoke(String prompt, Duration timeout) {
                return new AiResponse("response", 130_577);
            }
        };

        writer.invokeAndRecord(provider, "prompt", Duration.ofSeconds(5), "com.acme.Foo#bar()", 1);

        assertThat(progress.get(1)).contains("2m 10s 577ms");
    }

    /**
     * An agentic CLI reports its retries, backoff and quota limits on stderr while still
     * exiting 0 - so on a call that succeeded but took minutes, this file is the only
     * record of where those minutes went.
     */
    @Test
    void stderrFromASuccessfulCallIsKeptAlongsideTheResponse(@TempDir Path stateDir) throws Exception {
        TranscriptWriter writer = new TranscriptWriter(stateDir);
        AiProvider chattyProvider = new AiProvider() {
            public String id() {
                return "gemini";
            }

            public AiResponse invoke(String prompt, Duration timeout) {
                return new AiResponse("the tests", 1000, "429 rate limited, retrying in 60s");
            }
        };

        writer.invokeAndRecord(chattyProvider, "the prompt", Duration.ofSeconds(5), "com.acme.Foo#bar()", 1);

        Path unitDir = stateDir.resolve("transcripts").resolve("com.acme.Foo#bar()");
        assertThat(Files.readString(unitDir.resolve("1.stderr.log"))).contains("429 rate limited");
        assertThat(Files.readString(unitDir.resolve("1.response.md"))).isEqualTo("the tests");
    }

    @Test
    void noStderrFileIsWrittenWhenTheCliWroteNothingThere(@TempDir Path stateDir) throws Exception {
        TranscriptWriter writer = new TranscriptWriter(stateDir);
        RecordedAiProvider provider = new RecordedAiProvider("claude").enqueueResponse("the tests");

        writer.invokeAndRecord(provider, "the prompt", Duration.ofSeconds(5), "com.acme.Foo#bar()", 1);

        assertThat(stateDir.resolve("transcripts").resolve("com.acme.Foo#bar()").resolve("1.stderr.log"))
                .doesNotExist();
    }

    @Test
    void recordBuildFailureWritesTheRawLogUnderTheSameUnitDirectory(@TempDir Path stateDir) throws Exception {
        TranscriptWriter writer = new TranscriptWriter(stateDir);

        writer.recordBuildFailure("com.acme.Foo#bar()", "compile-repair-0", "Exit code: 1\n--- stdout ---\nboom");

        Path file = stateDir.resolve("transcripts").resolve("com.acme.Foo#bar()")
                .resolve("compile-repair-0.build.log");
        assertThat(Files.readString(file)).isEqualTo("Exit code: 1\n--- stdout ---\nboom");
    }

    @Test
    void recordBuildFailureEchoesToTheVerboseSinkWhenGiven(@TempDir Path stateDir) {
        List<String> echoed = new ArrayList<>();
        TranscriptWriter writer = new TranscriptWriter(stateDir, echoed::add);

        writer.recordBuildFailure("com.acme.Foo#bar()", "compile-repair-0", "boom");

        assertThat(echoed).singleElement().asString().contains("compile-repair-0").contains("mvn FAILED").contains("boom");
    }

    @Test
    void withoutAVerboseSinkNothingIsEchoedAnywhere(@TempDir Path stateDir) throws Exception {
        TranscriptWriter writer = new TranscriptWriter(stateDir);
        RecordedAiProvider provider = new RecordedAiProvider("claude").enqueueResponse("ok");

        // Passing simply confirms invokeAndRecord() tolerates a null sink - there is
        // nothing else to observe from the outside when it is absent.
        writer.invokeAndRecord(provider, "prompt", Duration.ofSeconds(5), "com.acme.Foo#bar()", 1);
    }

    @Test
    void multipleAttemptsForTheSameUnitEachGetTheirOwnFiles(@TempDir Path stateDir) throws Exception {
        TranscriptWriter writer = new TranscriptWriter(stateDir);
        RecordedAiProvider provider = new RecordedAiProvider("claude")
                .enqueueResponse("first attempt response")
                .enqueueResponse("second attempt response");

        writer.invokeAndRecord(provider, "attempt 1 prompt", Duration.ofSeconds(5), "com.acme.Foo#bar()", 1);
        writer.invokeAndRecord(provider, "attempt 2 prompt", Duration.ofSeconds(5), "com.acme.Foo#bar()", 2);

        Path unitDir = stateDir.resolve("transcripts").resolve("com.acme.Foo#bar()");
        assertThat(Files.readString(unitDir.resolve("1.response.md"))).isEqualTo("first attempt response");
        assertThat(Files.readString(unitDir.resolve("2.response.md"))).isEqualTo("second attempt response");
    }

    @Test
    void aUnitIdContainingCharactersThatAreInvalidInAFileNameIsSanitisedRatherThanFailing(
            @TempDir Path stateDir) throws Exception {
        // Real unit ids look like "com.acme.Foo#applyFee(BigDecimal,Currency)@PLAIN_UNIT"
        // (§8.1) - parentheses and commas are fine on both platforms, but a slash or a
        // colon in a mutant group segment is not (e.g. Windows forbids ':').
        TranscriptWriter writer = new TranscriptWriter(stateDir);
        RecordedAiProvider provider = new RecordedAiProvider("claude").enqueueResponse("response");

        writer.invokeAndRecord(provider, "prompt", Duration.ofSeconds(5),
                "com.acme.Foo#bar()@PLAIN_UNIT::CONDITIONALS_BOUNDARY@47", 1);

        // Whatever the exact sanitised directory name is, exactly one attempt-1 response
        // file must exist somewhere under transcripts/, and it must be readable.
        try (var walk = Files.walk(stateDir.resolve("transcripts"))) {
            List<Path> responseFiles = walk.filter(p -> p.getFileName().toString().equals("1.response.md")).toList();
            assertThat(responseFiles).hasSize(1);
            assertThat(Files.readString(responseFiles.get(0))).isEqualTo("response");
        }
    }

    @Test
    void writingCreatesTheStateDirectoryIfItDoesNotExistYet(@TempDir Path base) throws Exception {
        Path stateDir = base.resolve("does-not-exist-yet").resolve(".jtestforge");
        TranscriptWriter writer = new TranscriptWriter(stateDir);
        RecordedAiProvider provider = new RecordedAiProvider("claude").enqueueResponse("response");

        writer.invokeAndRecord(provider, "prompt", Duration.ofSeconds(5), "com.acme.Foo#bar()", 1);

        assertThat(stateDir.resolve("transcripts")).isDirectory();
    }
}
