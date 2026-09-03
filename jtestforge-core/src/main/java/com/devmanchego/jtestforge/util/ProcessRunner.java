package com.devmanchego.jtestforge.util;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Runs external processes with an explicit argument list and a bounded timeout.
 *
 * <p>Every external process JTestForge starts (Maven, an AI CLI, the standalone PITest
 * wrapper) goes through this class. Two properties are load-bearing and must not be
 * reintroduced ad hoc elsewhere in the codebase:
 *
 * <ul>
 *   <li><b>Argument list, never a single command string.</b> A joined string handed to
 *       a shell diverges in quoting rules between Windows and POSIX shells; a
 *       {@code List<String>} passed to {@link ProcessBuilder} does not.</li>
 *   <li><b>Both output streams are drained concurrently on their own threads and those
 *       threads are always joined before this method returns.</b> A process that writes
 *       more to stdout or stderr than the OS pipe buffer holds will block forever if the
 *       parent is not reading both streams while it waits — this is the classic
 *       {@code Process} deadlock, and it is avoided here, not left to callers.</li>
 * </ul>
 */
public final class ProcessRunner {

    private final Charset outputCharset;

    public ProcessRunner() {
        this(Charset.defaultCharset());
    }

    public ProcessRunner(Charset outputCharset) {
        this.outputCharset = Objects.requireNonNull(outputCharset, "outputCharset");
    }

    /**
     * Runs {@code command} to completion or until {@code timeout} elapses.
     *
     * @param command          the executable followed by its arguments; never joined
     *                         into a single string
     * @param workingDirectory directory the process is started in
     * @param environment      variables merged on top of the inherited environment;
     *                         empty map to inherit unchanged
     * @param stdin            content written to the process's standard input, then
     *                         closed; {@code null} to close stdin immediately without
     *                         writing anything
     * @param timeout          hard ceiling on wall-clock time; on expiry the process is
     *                         forcibly destroyed and {@link ProcessResult#timedOut()}
     *                         is {@code true}
     */
    public ProcessResult run(
            List<String> command,
            Path workingDirectory,
            Map<String, String> environment,
            String stdin,
            Duration timeout) throws InterruptedException {

        if (command == null || command.isEmpty()) {
            throw new IllegalArgumentException("command must not be null or empty");
        }
        Objects.requireNonNull(workingDirectory, "workingDirectory");
        Objects.requireNonNull(environment, "environment");
        Objects.requireNonNull(timeout, "timeout");

        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(workingDirectory.toFile());
        builder.environment().putAll(environment);

        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            throw new ProcessLaunchException(
                    "Failed to start process: " + String.join(" ", command), e);
        }

        StreamDrain stdoutDrain = new StreamDrain(process.getInputStream(), outputCharset);
        StreamDrain stderrDrain = new StreamDrain(process.getErrorStream(), outputCharset);
        Thread stdoutThread = new Thread(stdoutDrain, "jtestforge-process-stdout");
        Thread stderrThread = new Thread(stderrDrain, "jtestforge-process-stderr");
        stdoutThread.start();
        stderrThread.start();

        writeStdinAndClose(process, stdin);

        boolean finishedInTime = process.waitFor(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
        boolean timedOut = !finishedInTime;
        if (timedOut) {
            process.destroyForcibly();
            // A forcibly destroyed process still closes its streams, which lets the
            // drain threads reach end-of-stream; wait for that so no reader thread
            // leaks past this method returning.
            process.waitFor();
        }

        // Drain threads terminate once the corresponding stream reaches EOF, which
        // destroyForcibly() above guarantees even on timeout. No arbitrary join
        // timeout is used here on purpose: an unjoined thread is a silent leak.
        stdoutThread.join();
        stderrThread.join();

        int exitCode = timedOut ? ProcessResult.TIMED_OUT_EXIT_CODE : process.exitValue();
        return new ProcessResult(exitCode, stdoutDrain.content(), stderrDrain.content(), timedOut);
    }

    private void writeStdinAndClose(Process process, String stdin) {
        try (OutputStream in = process.getOutputStream()) {
            if (stdin != null && !stdin.isEmpty()) {
                in.write(stdin.getBytes(outputCharset));
                in.flush();
            }
        } catch (IOException e) {
            // The target process may have exited early (e.g. it does not read stdin at
            // all) and closed its side of the pipe first - a broken pipe here is
            // expected in that case, not a failure of this runner.
        }
    }

    /** Reads an {@link InputStream} to completion into a string, off the caller's thread. */
    private static final class StreamDrain implements Runnable {
        private final InputStream source;
        private final Charset charset;
        private volatile String content = "";

        StreamDrain(InputStream source, Charset charset) {
            this.source = source;
            this.charset = charset;
        }

        @Override
        public void run() {
            try {
                content = new String(source.readAllBytes(), charset);
            } catch (IOException e) {
                // Stream was closed from the other end (e.g. destroyForcibly) - whatever
                // was read up to that point is retained via readAllBytes' partial result
                // semantics being unavailable, so we fall back to what we have: empty.
                // In practice destroyForcibly() closes cleanly and this branch is rare.
                content = content == null ? "" : content;
            }
        }

        String content() {
            return content;
        }
    }

    /** Thrown when the target executable cannot be launched at all. */
    public static final class ProcessLaunchException extends UncheckedIOException {
        public ProcessLaunchException(String message, IOException cause) {
            super(message, cause);
        }
    }
}
