package com.devmanchego.jtestforge.util;

import java.io.ByteArrayOutputStream;
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
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Runs external processes with an explicit argument list and a bounded timeout.
 *
 * <p>Every external process JTestForge starts (Maven, an AI CLI, the standalone PITest
 * wrapper) goes through this class. Three properties are load-bearing and must not be
 * reintroduced ad hoc elsewhere in the codebase:
 *
 * <ul>
 *   <li><b>Argument list, never a single command string.</b> A joined string handed to
 *       a shell diverges in quoting rules between Windows and POSIX shells; a
 *       {@code List<String>} passed to {@link ProcessBuilder} does not.</li>
 *   <li><b>Both output streams are drained concurrently on their own threads.</b> A
 *       process that writes more to stdout or stderr than the OS pipe buffer holds will
 *       block forever if the parent is not reading both streams while it waits — this is
 *       the classic {@code Process} deadlock, and it is avoided here, not left to
 *       callers.</li>
 *   <li><b>The whole process TREE is killed, not just the direct child.</b> See
 *       {@link #destroyTree}: on Windows a {@code .cmd}/{@code .bat} wrapper that spawns
 *       {@code node.exe} leaves that grandchild alive when only the direct child is
 *       destroyed, and the grandchild keeps the inherited pipe open - which turns a
 *       bounded timeout into an unbounded hang.</li>
 * </ul>
 *
 * <p>Being the one chokepoint every external process goes through also makes this the one
 * place {@code -v}/{@code --verbose} needs to hook into to echo every command JTestForge
 * runs - Maven ({@code mvn test-compile}, {@code mvn test jacoco:report}, ...), the AI CLI,
 * PIT - without each caller repeating that wiring.
 */
public final class ProcessRunner {

    /**
     * How long a drain thread is given to reach end-of-stream once the process it was
     * reading is gone. Only ever paid when something still holds the inherited pipe open,
     * which is the one case that used to hang the whole run indefinitely. Short on
     * purpose: the process is already gone by then, so its output has already been
     * flushed - this grace only covers the handover, not real work.
     */
    private static final Duration DRAIN_GRACE = Duration.ofSeconds(5);

    /**
     * How often the descendant snapshot is refreshed while waiting. A process's
     * parent-child links die with it, so a descendant that OUTLIVES its parent can only
     * be reached through handles captured while the parent was still alive.
     */
    private static final Duration DESCENDANT_POLL = Duration.ofSeconds(2);

    private final Charset outputCharset;
    private final Consumer<String> commandSink;
    private final Consumer<String> timingSink;

    public ProcessRunner() {
        this(Charset.defaultCharset());
    }

    public ProcessRunner(Charset outputCharset) {
        this(outputCharset, null);
    }

    /** @param commandSink receives one line per command launched when not null - see {@code -v/--verbose} */
    public ProcessRunner(Charset outputCharset, Consumer<String> commandSink) {
        this(outputCharset, commandSink, null);
    }

    /**
     * @param commandSink receives one line per command launched when not null - see {@code -v/--verbose}
     * @param timingSink  receives one content-free line per process with the wall-clock
     *                    breakdown (launch, stdin, first byte on each stream, exit). This
     *                    is what tells "the CLI is thinking" apart from "the CLI never
     *                    started" or "the CLI answered but nothing closed the pipe".
     */
    public ProcessRunner(Charset outputCharset, Consumer<String> commandSink, Consumer<String> timingSink) {
        this.outputCharset = Objects.requireNonNull(outputCharset, "outputCharset");
        this.commandSink = commandSink;
        this.timingSink = timingSink;
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
     * @param timeout          hard ceiling on wall-clock time; on expiry the process tree
     *                         is forcibly destroyed and {@link ProcessResult#timedOut()}
     *                         is {@code true}. Whatever the process printed before being
     *                         killed is still returned - a partial answer is exactly what
     *                         is needed to diagnose why it was too slow.
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

        if (commandSink != null) {
            commandSink.accept("$ " + String.join(" ", command) + "  (in " + workingDirectory + ")"
                    + environmentOverridesSuffix(environment));
        }

        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(workingDirectory.toFile());
        builder.environment().putAll(environment);

        long startNanos = System.nanoTime();
        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            throw new ProcessLaunchException(
                    "Failed to start process: " + String.join(" ", command), e);
        }
        long launchMillis = millisSince(startNanos);

        StreamDrain stdoutDrain = new StreamDrain(process.getInputStream(), outputCharset, startNanos);
        StreamDrain stderrDrain = new StreamDrain(process.getErrorStream(), outputCharset, startNanos);
        Thread stdoutThread = drainThread(stdoutDrain, "stdout");
        Thread stderrThread = drainThread(stderrDrain, "stderr");
        stdoutThread.start();
        stderrThread.start();

        long beforeStdinNanos = System.nanoTime();
        writeStdinAndClose(process, stdin);
        long stdinMillis = millisSince(beforeStdinNanos);

        Wait wait = awaitExit(process, timeout);
        boolean timedOut = !wait.exited();
        if (timedOut) {
            destroyTree(process, wait.descendants());
            process.waitFor();
        }
        long exitMillis = millisSince(startNanos);

        boolean drained = joinDrains(process, wait.descendants(), stdoutThread, stderrThread);

        int exitCode = timedOut ? ProcessResult.TIMED_OUT_EXIT_CODE : process.exitValue();
        emitTiming(command, launchMillis, stdinMillis, stdin == null ? 0 : stdin.length(),
                stdoutDrain, stderrDrain, exitMillis, exitCode, timedOut, drained);
        if (commandSink != null) {
            commandSink.accept(timedOut ? "  -> timed out" : "  -> exit " + exitCode);
        }
        return new ProcessResult(exitCode, stdoutDrain.content(), stderrDrain.content(), timedOut);
    }

    /**
     * Waits for the process, refreshing a snapshot of its descendants as it goes.
     *
     * <p>The snapshot is the whole point: {@link Process#descendants()} walks live
     * parent-child links, and those links die with the parent. A wrapper that spawns
     * {@code node.exe} and exits immediately leaves nothing to traverse afterwards, so the
     * handles have to be captured while it is still running or the grandchild becomes
     * unreachable - still alive, still holding the inherited pipe.
     */
    private Wait awaitExit(Process process, Duration timeout) throws InterruptedException {
        long deadlineNanos = System.nanoTime() + timeout.toNanos();
        List<ProcessHandle> descendants = List.of();
        while (true) {
            List<ProcessHandle> snapshot = process.descendants().toList();
            if (!snapshot.isEmpty()) {
                descendants = snapshot;
            }
            long remaining = deadlineNanos - System.nanoTime();
            if (remaining <= 0) {
                return new Wait(!process.isAlive(), descendants);
            }
            long slice = Math.min(DESCENDANT_POLL.toNanos(), remaining);
            if (process.waitFor(Math.max(1, Duration.ofNanos(slice).toMillis()), TimeUnit.MILLISECONDS)) {
                return new Wait(true, descendants);
            }
        }
    }

    /**
     * {@link Process#destroyForcibly()} terminates the direct child only. When that child
     * is a {@code .cmd}/{@code .bat} wrapper (a corporate {@code mvn.cmd}, a {@code
     * gemini.cmd} that shells out to {@code node.exe}), the grandchild survives AND keeps
     * the stdout/stderr pipe it inherited open - so the drain threads never see EOF and
     * the call hangs long past the timeout that was supposed to bound it. Observed in
     * practice: a run still waiting on one AI call an hour after a five-minute timeout.
     */
    private void destroyTree(Process process, List<ProcessHandle> knownDescendants) {
        List<ProcessHandle> live = process.descendants().toList();
        process.destroyForcibly();
        live.forEach(ProcessHandle::destroyForcibly);
        knownDescendants.forEach(ProcessHandle::destroyForcibly);
    }

    /**
     * Waits for both drains to finish, escalating to a tree kill if they do not: a stream
     * that has not reached EOF although the process itself exited means some descendant it
     * spawned and never waited for is still holding the pipe.
     *
     * <p>Gives up rather than waiting forever if even that does not free the pipe. The
     * process is gone and its output is already flushed by then, so continuing with what
     * was read costs nothing - whereas blocking here is precisely the unbounded hang this
     * whole path exists to prevent. The drain threads are daemons, so an abandoned one can
     * never keep the JVM alive.
     *
     * @return whether both drains actually reached end-of-stream
     */
    private boolean joinDrains(Process process, List<ProcessHandle> knownDescendants,
                               Thread stdoutThread, Thread stderrThread) throws InterruptedException {
        if (joinWithin(stdoutThread, stderrThread, DRAIN_GRACE)) {
            return true;
        }
        destroyTree(process, knownDescendants);
        return joinWithin(stdoutThread, stderrThread, DRAIN_GRACE);
    }

    /** The outcome of {@link #awaitExit}: whether the process ended on its own, and what it had spawned. */
    private record Wait(boolean exited, List<ProcessHandle> descendants) {
    }

    private boolean joinWithin(Thread stdoutThread, Thread stderrThread, Duration grace)
            throws InterruptedException {
        long deadlineNanos = System.nanoTime() + grace.toNanos();
        stdoutThread.join(remainingMillis(deadlineNanos));
        stderrThread.join(remainingMillis(deadlineNanos));
        return !stdoutThread.isAlive() && !stderrThread.isAlive();
    }

    /** Never returns 0: {@link Thread#join(long)} reads 0 as "wait forever", which is the one thing this must not do. */
    private long remainingMillis(long deadlineNanos) {
        return Math.max(1, Duration.ofNanos(deadlineNanos - System.nanoTime()).toMillis());
    }

    /**
     * Daemon on purpose: a drain thread that never reaches EOF (see {@link #joinDrains})
     * must not be able to keep the JVM alive after the run itself has moved on.
     */
    private Thread drainThread(StreamDrain drain, String streamName) {
        Thread thread = new Thread(drain, "jtestforge-process-" + streamName);
        thread.setDaemon(true);
        return thread;
    }

    private void emitTiming(List<String> command, long launchMillis, long stdinMillis, int stdinChars,
                            StreamDrain stdout, StreamDrain stderr, long exitMillis, int exitCode,
                            boolean timedOut, boolean drained) {
        if (timingSink == null) {
            return;
        }
        StringBuilder line = new StringBuilder("  [timing] ").append(executableName(command))
                .append(": launch ").append(launchMillis).append("ms");
        if (stdinChars > 0) {
            line.append(" | stdin ").append(stdinChars).append(" chars in ").append(stdinMillis).append("ms");
        }
        line.append(" | 1st stderr ").append(firstByte(stderr))
                .append(" | 1st stdout ").append(firstByte(stdout))
                .append(" | ").append(timedOut ? "TIMED OUT" : "exit " + exitCode)
                .append(" after ").append(DurationFormat.humanReadable(exitMillis))
                .append(" | out ").append(stdout.byteCount()).append("B, err ").append(stderr.byteCount()).append("B");
        if (!drained) {
            line.append(" | WARNING: a stream never closed - a descendant process is still holding it");
        }
        timingSink.accept(line.toString());
    }

    private String firstByte(StreamDrain drain) {
        long millis = drain.firstByteMillis();
        return millis < 0 ? "(none)" : DurationFormat.humanReadable(millis);
    }

    private String executableName(List<String> command) {
        String executable = command.get(0);
        int separator = Math.max(executable.lastIndexOf('\\'), executable.lastIndexOf('/'));
        return separator < 0 ? executable : executable.substring(separator + 1);
    }

    private long millisSince(long startNanos) {
        return Duration.ofNanos(System.nanoTime() - startNanos).toMillis();
    }

    /**
     * The point of echoing this at all: {@code JAVA_HOME} set here only overrides what the
     * child process's OWN environment resolution ends up using if that process actually
     * consults {@code %JAVA_HOME%} rather than resolving {@code java} straight off
     * {@code PATH} - a corporate {@code mvn.cmd} wrapper is exactly the kind of script that
     * might not. Seeing the literal override sent is the fastest way to tell "JTestForge
     * sent the wrong value" apart from "JTestForge's value was sent but ignored downstream".
     */
    private String environmentOverridesSuffix(Map<String, String> environment) {
        if (environment.isEmpty()) {
            return "";
        }
        return "  [env: " + environment.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(java.util.stream.Collectors.joining(", ")) + "]";
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

    /**
     * Reads an {@link InputStream} to completion into a string, off the caller's thread,
     * recording when its first byte arrived.
     *
     * <p>Read in chunks rather than via {@code readAllBytes()} for two reasons that both
     * matter when a process has to be killed: whatever it printed before the kill is
     * retained instead of discarded, and the first-byte timestamp distinguishes a CLI that
     * never started from one that started and then spent minutes thinking.
     */
    private static final class StreamDrain implements Runnable {
        private final InputStream source;
        private final Charset charset;
        private final long startNanos;
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private volatile long firstByteMillis = -1;

        StreamDrain(InputStream source, Charset charset, long startNanos) {
            this.source = source;
            this.charset = charset;
            this.startNanos = startNanos;
        }

        @Override
        public void run() {
            byte[] chunk = new byte[8192];
            try {
                int read;
                while ((read = source.read(chunk)) != -1) {
                    if (read > 0 && firstByteMillis < 0) {
                        firstByteMillis = Duration.ofNanos(System.nanoTime() - startNanos).toMillis();
                    }
                    synchronized (buffer) {
                        buffer.write(chunk, 0, read);
                    }
                }
            } catch (IOException e) {
                // Stream closed from the other end (e.g. destroyForcibly) - everything
                // read up to that point is still in the buffer, and is still worth having.
            }
        }

        String content() {
            synchronized (buffer) {
                return buffer.toString(charset);
            }
        }

        int byteCount() {
            synchronized (buffer) {
                return buffer.size();
            }
        }

        long firstByteMillis() {
            return firstByteMillis;
        }
    }

    /** Thrown when the target executable cannot be launched at all. */
    public static final class ProcessLaunchException extends UncheckedIOException {
        public ProcessLaunchException(String message, IOException cause) {
            super(message, cause);
        }
    }
}
