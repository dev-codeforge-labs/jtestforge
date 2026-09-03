package com.devmanchego.jtestforge.cli;

import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;

/**
 * The single channel for output meant for a human running JTestForge interactively.
 *
 * <p>Per jtestforge-specification.md §17: "Console output for humans goes through a
 * dedicated ConsoleOutput class, never System.out scattered across the codebase." Every
 * command implementation prints through this class instead of calling
 * {@code System.out}/{@code System.err} directly, so output formatting (and, later,
 * a {@code --quiet}/machine-readable mode) has one place to change.
 *
 * <p>Writers are injected rather than hard-coded to {@code System.out}/{@code System.err}:
 * Picocli captures a command's output stream at {@code CommandLine} construction time, not
 * at execution time, so a subcommand must write through {@code spec.commandLine().getOut()}
 * / {@code getErr()} for a test to be able to capture it (the same fix already applied to
 * the root command in phase 0).
 */
public final class ConsoleOutput {

    private final PrintWriter out;
    private final PrintWriter err;

    /** Writes to the real process streams - used by {@code main}, never by a subcommand. */
    public ConsoleOutput() {
        this(new PrintWriter(System.out, true, StandardCharsets.UTF_8),
                new PrintWriter(System.err, true, StandardCharsets.UTF_8));
    }

    public ConsoleOutput(PrintWriter out, PrintWriter err) {
        this.out = out;
        this.err = err;
    }

    public void info(String message) {
        out.println(message);
    }

    public void warn(String message) {
        out.println("WARNING: " + message);
    }

    public void error(String message) {
        err.println("ERROR: " + message);
    }
}
