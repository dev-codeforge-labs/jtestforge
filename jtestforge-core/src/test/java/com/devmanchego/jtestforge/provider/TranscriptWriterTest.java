package com.devmanchego.jtestforge.provider;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

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
        assertThat(stateDir.resolve("transcripts").resolve("com.acme.Foo#bar()").resolve("1.response.md"))
                .doesNotExist();
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
