package com.devmanchego.jtestforge.provider;

import com.devmanchego.jtestforge.util.AtomicFileWriter;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;

/**
 * Records every prompt and response to {@code <stateDir>/transcripts/<unitId>/<attempt>.
 * {prompt,response}.md} — jtestforge-specification.md §12.2: "This is the single most
 * valuable artifact when tuning prompts, and it costs nothing to keep."
 *
 * <p>Deliberately not part of {@link AiProvider} itself. The SPI's {@code invoke(prompt,
 * timeout)} carries no unit or attempt identity - by design, so any provider (a real CLI,
 * a future HTTP provider, {@link RecordedAiProvider} in tests) stays a pure "how do I talk
 * to this thing" concern. Transcript writing is a cross-cutting concern the orchestration
 * layer applies <em>around</em> a call to any of them, which is exactly what this class
 * does: it wraps one {@link AiProvider#invoke} call and writes the transcript regardless
 * of whether that call succeeds.
 */
public final class TranscriptWriter {

    private final Path stateDir;

    public TranscriptWriter(Path stateDir) {
        this.stateDir = Objects.requireNonNull(stateDir, "stateDir");
    }

    /**
     * @throws ProviderException whatever {@code provider.invoke} threw, after the prompt
     *                           has already been written - a failed attempt's prompt is
     *                           exactly the one most worth keeping for later diagnosis
     */
    public AiResponse invokeAndRecord(
            AiProvider provider, String prompt, Duration timeout, String unitId, int attempt)
            throws ProviderException {
        Path unitDir = stateDir.resolve("transcripts").resolve(sanitise(unitId));
        writeQuietly(unitDir.resolve(attempt + ".prompt.md"), prompt);

        AiResponse response = provider.invoke(prompt, timeout);

        writeQuietly(unitDir.resolve(attempt + ".response.md"), response.content());
        return response;
    }

    /**
     * Replaces characters that are invalid in a file name on at least one supported
     * platform (Windows forbids {@code : \ / * ? " < > |}) with {@code _}. A real unit id
     * looks like {@code com.acme.Foo#applyFee(BigDecimal,Currency)@PLAIN_UNIT} (§8.1) or,
     * in pass 2, carries a {@code ::MUTATOR@line} suffix - parentheses and commas are fine
     * on both platforms, but a mutant group's {@code ::} is not something to route through
     * as a literal path separator.
     */
    private String sanitise(String unitId) {
        return unitId.replaceAll("[:\\\\/*?\"<>|]", "_");
    }

    private void writeQuietly(Path file, String content) {
        try {
            AtomicFileWriter.write(file, content == null ? "" : content);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write transcript file " + file, e);
        }
    }
}
