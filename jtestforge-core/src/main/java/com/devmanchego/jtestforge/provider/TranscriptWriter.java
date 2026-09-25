package com.devmanchego.jtestforge.provider;

import com.devmanchego.jtestforge.util.AtomicFileWriter;
import com.devmanchego.jtestforge.util.DurationFormat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.function.Consumer;

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
 *
 * <p>A failed attempt gets a {@code .response.md} too, not just the prompt: the full
 * process diagnostics {@link ProviderException} carries (command, exit code, complete
 * stdout and stderr) - the one-line, sometimes mojibake-mangled message state.json keeps
 * is not enough to diagnose a transport failure after the fact.
 *
 * <p>{@link #recordBuildFailure} extends the same discipline to the other process JTestForge
 * invokes throughout a unit's lifecycle - Maven. A build that fails for a reason
 * {@code CompilerErrorParser} does not recognise as a javac diagnostic deserves exactly the
 * same complete record, not silence.
 *
 * <p>Two separate output channels exist on purpose, at different defaults. {@code
 * progressSink} is meant to run on every invocation - one short line per request and per
 * response, naming no file content, so a long run does not look stalled. {@code
 * verboseSink} ({@code -v/--verbose}) is the full prompt/response text, opt-in, for when
 * that short line is not enough to diagnose something.
 */
public final class TranscriptWriter {

    /**
     * How often a "still waiting" line is emitted while blocked inside {@code
     * provider.invoke(...)}. A first-time run of a CLI like {@code gemini} can sit on an
     * interactive/browser authentication step with no other output at all - without this,
     * the whole run looks hung rather than merely slow.
     */
    private static final Duration DEFAULT_HEARTBEAT_INTERVAL = Duration.ofSeconds(20);

    private final Path stateDir;
    private final Consumer<String> progressSink;
    private final Consumer<String> verboseSink;
    private final Duration heartbeatInterval;

    public TranscriptWriter(Path stateDir) {
        this(stateDir, null, null);
    }

    /** @param verboseSink receives the full prompt/response text per request when not null - see {@code -v/--verbose} */
    public TranscriptWriter(Path stateDir, Consumer<String> verboseSink) {
        this(stateDir, null, verboseSink);
    }

    /**
     * @param progressSink receives one short, content-free line per request and per
     *                     response when not null - on by default, regardless of
     *                     {@code -v/--verbose}
     * @param verboseSink  receives the full prompt/response text per request when not
     *                     null - see {@code -v/--verbose}
     */
    public TranscriptWriter(Path stateDir, Consumer<String> progressSink, Consumer<String> verboseSink) {
        this(stateDir, progressSink, verboseSink, DEFAULT_HEARTBEAT_INTERVAL);
    }

    /** @param heartbeatInterval test-only hook to shrink the wait between heartbeat lines; production always uses the default. */
    TranscriptWriter(Path stateDir, Consumer<String> progressSink, Consumer<String> verboseSink,
                     Duration heartbeatInterval) {
        this.stateDir = Objects.requireNonNull(stateDir, "stateDir");
        this.progressSink = progressSink;
        this.verboseSink = verboseSink;
        this.heartbeatInterval = Objects.requireNonNull(heartbeatInterval, "heartbeatInterval");
    }

    /**
     * @throws ProviderException whatever {@code provider.invoke} threw, after the prompt
     *                           and (on failure) the full diagnostics have already been
     *                           written - a failed attempt is exactly the one most worth
     *                           keeping for later diagnosis
     */
    public AiResponse invokeAndRecord(
            AiProvider provider, String prompt, Duration timeout, String unitId, int attempt)
            throws ProviderException {
        Path unitDir = stateDir.resolve("transcripts").resolve(sanitise(unitId));
        writeQuietly(unitDir.resolve(attempt + ".prompt.md"), prompt);
        echoVerbose(unitId, attempt, "-> " + provider.id() + " (prompt: " + prompt.length() + " chars)\n" + prompt);
        echoProgress("-> " + provider.id() + ": requesting tests (attempt " + attempt + ")");

        Heartbeat heartbeat = Heartbeat.start(progressSink, provider.id(), heartbeatInterval);
        try {
            AiResponse response = provider.invoke(prompt, timeout);
            writeQuietly(unitDir.resolve(attempt + ".response.md"), response.content());
            if (!response.stderr().isBlank()) {
                // Kept for a SUCCESSFUL call too: an agentic CLI reports its retries,
                // backoff and quota limits here while still exiting 0, and that is the
                // only record of why a call that worked took minutes to do it.
                writeQuietly(unitDir.resolve(attempt + ".stderr.log"), response.stderr());
            }
            echoVerbose(unitId, attempt, "<- " + provider.id() + " OK, " + response.durationMillis() + "ms, "
                    + response.content().length() + " chars\n" + response.content());
            echoProgress("<- " + provider.id() + ": received (" + DurationFormat.humanReadable(response.durationMillis()) + ")");
            return response;
        } catch (ProviderException e) {
            String diagnosticDump = diagnosticDump(e);
            writeQuietly(unitDir.resolve(attempt + ".response.md"), diagnosticDump);
            echoVerbose(unitId, attempt, "<- " + provider.id() + " FAILED: " + e.getMessage() + "\n" + diagnosticDump);
            echoProgress("<- " + provider.id() + ": FAILED (" + e.getMessage() + ")");
            throw e;
        } finally {
            heartbeat.stop();
        }
    }

    /**
     * @param unitId the failing unit
     * @param label  distinguishes this build from the AI-attempt files sharing the same
     *               unit directory, e.g. {@code "compile-repair-2"}
     * @param log    the build's complete stdout and stderr
     */
    public void recordBuildFailure(String unitId, String label, String log) {
        Path unitDir = stateDir.resolve("transcripts").resolve(sanitise(unitId));
        writeQuietly(unitDir.resolve(label + ".build.log"), log);
        if (verboseSink != null) {
            verboseSink.accept("[" + unitId + " " + label + "] mvn FAILED\n" + log);
        }
    }

    private String diagnosticDump(ProviderException e) {
        return e.diagnostics().map(d -> "FAILED: " + e.getMessage()
                        + "\nCommand: " + d.command()
                        + "\nExit code: " + (d.exitCode() == Integer.MIN_VALUE ? "(timed out)" : d.exitCode())
                        + "\n\n--- stdout ---\n" + d.stdout()
                        + "\n--- stderr ---\n" + d.stderr())
                .orElse("FAILED: " + e.getMessage());
    }

    private void echoVerbose(String unitId, int attempt, String message) {
        if (verboseSink != null) {
            verboseSink.accept("[" + unitId + " attempt " + attempt + "] " + message);
        }
    }

    private void echoProgress(String message) {
        if (progressSink != null) {
            progressSink.accept("  " + message);
        }
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

    /**
     * A daemon thread that emits a "still waiting" progress line at a fixed interval while
     * a blocking {@code provider.invoke(...)} call is in flight - {@link #stop()} simply
     * interrupts it, which is exactly how its own sleep loop ends, whether or not it ever
     * got the chance to fire.
     */
    private static final class Heartbeat {
        private final Thread thread;

        private Heartbeat(Thread thread) {
            this.thread = thread;
        }

        static Heartbeat start(Consumer<String> sink, String providerId, Duration interval) {
            if (sink == null) {
                return new Heartbeat(null);
            }
            long startedAt = System.currentTimeMillis();
            Thread thread = new Thread(() -> {
                try {
                    while (true) {
                        Thread.sleep(interval.toMillis());
                        long elapsed = System.currentTimeMillis() - startedAt;
                        sink.accept("  -> " + providerId + ": still waiting (" + DurationFormat.humanReadable(elapsed)
                                + ") - if this is the first run, it may be stuck on a CLI authentication step");
                    }
                } catch (InterruptedException expected) {
                    // stop() interrupts the sleep to end the loop - this is the normal exit path.
                }
            }, "jtestforge-heartbeat-" + providerId);
            thread.setDaemon(true);
            thread.start();
            return new Heartbeat(thread);
        }

        void stop() {
            if (thread != null) {
                thread.interrupt();
            }
        }
    }

    private void writeQuietly(Path file, String content) {
        try {
            AtomicFileWriter.write(file, content == null ? "" : content);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write transcript file " + file, e);
        }
    }
}
