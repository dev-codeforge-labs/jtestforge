package com.devmanchego.jtestforge.model;

import java.util.Objects;

/**
 * One javac diagnostic extracted from Maven's console output —
 * jtestforge-specification.md §13.1. Feeds the {@code fixCompilation} prompt (§6.1).
 *
 * @param file    source file the diagnostic refers to, as printed by javac (usually
 *                absolute)
 * @param line    1-based line number
 * @param column  1-based column number
 * @param message the diagnostic text, including any continuation lines (e.g. javac's
 *                {@code symbol:}/{@code location:} detail) joined with newlines
 */
public record CompilerError(String file, int line, int column, String message) {

    public CompilerError {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(message, "message");
    }
}
