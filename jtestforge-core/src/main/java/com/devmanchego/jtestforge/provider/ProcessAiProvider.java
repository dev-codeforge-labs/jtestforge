package com.devmanchego.jtestforge.provider;

import com.devmanchego.jtestforge.config.PromptDelivery;
import com.devmanchego.jtestforge.util.ExecutableResolver;
import com.devmanchego.jtestforge.util.ProcessResult;
import com.devmanchego.jtestforge.util.ProcessRunner;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Talks to an AI CLI as a child process — jtestforge-specification.md §12.1, §12.2. A
 * single implementation covers both Claude CLI and Gemini CLI; they differ only in
 * {@code command}, {@code args} and {@code promptDelivery}.
 *
 * <p>Retries apply to <b>transport failures only</b> - a non-zero exit, a timeout, or
 * empty stdout. A well-formed but useless answer is never retried here; that is the
 * response parser's and the quality gates' job, at a layer that can actually tell the
 * difference between "useless" and "wrong".
 */
public final class ProcessAiProvider implements AiProvider {

    private final String id;
    private final ProcessRunner processRunner;
    private final String command;
    private final List<String> args;
    private final PromptDelivery promptDelivery;
    private final int transportRetries;
    private final Path workingDirectory;
    private final Map<String, String> environment;

    /** Runs the CLI with the JVM's own current directory as its working directory. */
    public ProcessAiProvider(String id, ProcessRunner processRunner, String command,
                              List<String> args, PromptDelivery promptDelivery, int transportRetries) {
        this(id, processRunner, command, args, promptDelivery, transportRetries,
                Path.of("").toAbsolutePath());
    }

    /**
     * @param workingDirectory directory the CLI process is started in. The orchestration
     *                         layer passes the target module's path here; there is no
     *                         reason for the AI CLI to run from wherever JTestForge's own
     *                         JVM happens to have been launched.
     */
    public ProcessAiProvider(String id, ProcessRunner processRunner, String command,
                              List<String> args, PromptDelivery promptDelivery, int transportRetries,
                              Path workingDirectory) {
        this(id, processRunner, command, args, promptDelivery, transportRetries, workingDirectory, Map.of());
    }

    /**
     * @param environment variables merged on top of the inherited environment for this CLI
     *                    only - {@code aiProvider.providers.*.env}. JTestForge stays
     *                    agnostic about what they mean; a corporate setup routinely needs
     *                    some (a writable config directory, a proxy, a debug switch).
     */
    public ProcessAiProvider(String id, ProcessRunner processRunner, String command,
                              List<String> args, PromptDelivery promptDelivery, int transportRetries,
                              Path workingDirectory, Map<String, String> environment) {
        this.environment = Map.copyOf(Objects.requireNonNull(environment, "environment"));
        this.id = Objects.requireNonNull(id, "id");
        this.processRunner = Objects.requireNonNull(processRunner, "processRunner");
        this.command = Objects.requireNonNull(command, "command");
        this.args = List.copyOf(args);
        this.promptDelivery = Objects.requireNonNull(promptDelivery, "promptDelivery");
        this.transportRetries = transportRetries;
        this.workingDirectory = Objects.requireNonNull(workingDirectory, "workingDirectory");
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public AiResponse invoke(String prompt, Duration timeout) throws ProviderException {
        long startNanos = System.nanoTime();
        String resolvedExecutable = resolveExecutable();

        ProviderException lastFailure = null;
        for (int attempt = 0; attempt <= transportRetries; attempt++) {
            Path promptFile = null;
            try {
                List<String> command = new ArrayList<>();
                command.add(resolvedExecutable);
                command.addAll(args);

                String stdin = null;
                if (promptDelivery == PromptDelivery.STDIN) {
                    stdin = prompt;
                } else if (promptDelivery == PromptDelivery.ARGUMENT) {
                    command.add(prompt);
                } else {
                    promptFile = writePromptFile(prompt);
                    command.add(promptFile.toString());
                }

                ProcessResult result = processRunner.run(command, workingDirectory, environment, stdin, timeout);

                String failureReason = transportFailureReason(result);
                if (failureReason == null) {
                    return new AiResponse(result.stdout(), elapsedMillis(startNanos), result.stderr());
                }
                lastFailure = new ProviderException(
                        "\"" + id + "\" attempt " + (attempt + 1) + "/" + (transportRetries + 1)
                                + " failed: " + failureReason,
                        new ProviderException.Diagnostics(String.join(" ", command),
                                result.timedOut() ? Integer.MIN_VALUE : result.exitCode(),
                                result.stdout(), result.stderr()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ProviderException("Interrupted while invoking \"" + id + "\"", e);
            } finally {
                deleteQuietly(promptFile);
            }
        }
        throw lastFailure;
    }

    private String transportFailureReason(ProcessResult result) {
        if (result.timedOut()) {
            return "timed out";
        }
        if (result.exitCode() != 0) {
            return "exited with code " + result.exitCode()
                    + (result.stderr().isBlank() ? "" : ": " + firstLine(result.stderr()));
        }
        if (result.stdout().isBlank()) {
            return "produced no output";
        }
        return null;
    }

    private String firstLine(String text) {
        int newline = text.indexOf('\n');
        return newline < 0 ? text : text.substring(0, newline);
    }

    /**
     * Resolves a bare command name like {@code "claude"} to an actually-launchable path.
     * {@link ProcessBuilder} does not search {@code PATHEXT} extensions for a bare name
     * the way a shell does - the same reasoning as {@code MavenRunner} (phase 7): an AI
     * CLI is exactly as likely to ship a bare POSIX launcher alongside its
     * Windows {@code .cmd} counterpart.
     */
    private String resolveExecutable() {
        return ExecutableResolver.resolve(command, ExecutableResolver.systemPathDirectories())
                .map(Path::toString)
                .orElse(command);
    }

    private Path writePromptFile(String prompt) {
        try {
            Path file = Files.createTempFile("jtestforge-prompt-", ".txt");
            Files.writeString(file, prompt);
            return file;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write prompt file for \"" + id + "\"", e);
        }
    }

    private void deleteQuietly(Path file) {
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // Best-effort cleanup of a temp file; leaving one behind is harmless.
        }
    }

    private long elapsedMillis(long startNanos) {
        return Duration.ofNanos(System.nanoTime() - startNanos).toMillis();
    }
}
