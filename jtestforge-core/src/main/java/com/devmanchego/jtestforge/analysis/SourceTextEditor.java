package com.devmanchego.jtestforge.analysis;

import com.github.javaparser.Position;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Applies edits to a source file as text, at offsets derived from AST positions.
 *
 * <p>Deliberately not {@code LexicalPreservingPrinter}. LPP preserves most formatting but
 * not all of it: removing a member leaves the member's orphaned indentation behind as a
 * whitespace-only line, and an import inserted at the head of the list is emitted directly
 * against the package declaration, swallowing the blank line that separated them. Either
 * artifact is a change the developer never asked for, and both break the byte-identical
 * revert that this phase's safety rests on.
 *
 * <p>Splicing text at AST offsets keeps the untouched regions bit-for-bit as they were
 * found, which makes "insert exactly this block" and "delete exactly that block" the two
 * halves of one reversible operation.
 *
 * <p>Edits are collected and applied together, back to front, so that each edit's offsets
 * still refer to the original text at the moment it is applied.
 */
final class SourceTextEditor {

    private final String originalText;
    private final int[] lineStartOffsets;
    private final List<Edit> edits = new ArrayList<>();

    SourceTextEditor(String originalText) {
        this.originalText = originalText;
        this.lineStartOffsets = computeLineStartOffsets(originalText);
    }

    String originalText() {
        return originalText;
    }

    /** JavaParser positions are 1-based in both line and column. */
    int offsetOf(Position position) {
        int lineIndex = Math.min(position.line - 1, lineStartOffsets.length - 1);
        return lineStartOffsets[lineIndex] + (position.column - 1);
    }

    /** Offset of the first character of the line containing {@code offset}. */
    int startOfLineContaining(int offset) {
        int cursor = offset;
        while (cursor > 0 && originalText.charAt(cursor - 1) != '\n') {
            cursor--;
        }
        return cursor;
    }

    /**
     * Offset just past the line terminator that ends the line containing {@code offset},
     * handling both {@code \n} and {@code \r\n}.
     */
    int pastEndOfLineContaining(int offset) {
        int cursor = offset;
        while (cursor < originalText.length() && originalText.charAt(cursor) != '\n') {
            cursor++;
        }
        return cursor < originalText.length() ? cursor + 1 : cursor;
    }

    /** Whether the line immediately before {@code lineStart} is blank. */
    boolean isPrecededByBlankLine(int lineStart) {
        if (lineStart < 1 || originalText.charAt(lineStart - 1) != '\n') {
            return false;
        }
        int previousLineStart = startOfLineContaining(lineStart - 1);
        return originalText.substring(previousLineStart, lineStart).isBlank();
    }

    /** The indentation (leading whitespace) of the line containing {@code offset}. */
    String indentationOfLineContaining(int offset) {
        int lineStart = startOfLineContaining(offset);
        int cursor = lineStart;
        while (cursor < originalText.length() && (originalText.charAt(cursor) == ' '
                || originalText.charAt(cursor) == '\t')) {
            cursor++;
        }
        return originalText.substring(lineStart, cursor);
    }

    void insert(int offset, String text) {
        edits.add(new Edit(offset, offset, text));
    }

    void delete(int startInclusive, int endExclusive) {
        edits.add(new Edit(startInclusive, endExclusive, ""));
    }

    boolean hasEdits() {
        return !edits.isEmpty();
    }

    String apply() {
        StringBuilder result = new StringBuilder(originalText);
        List<Edit> orderedBackToFront = new ArrayList<>(edits);
        orderedBackToFront.sort(Comparator.comparingInt(Edit::startInclusive).reversed());
        for (Edit edit : orderedBackToFront) {
            result.replace(edit.startInclusive(), edit.endExclusive(), edit.replacement());
        }
        return result.toString();
    }

    private static int[] computeLineStartOffsets(String text) {
        List<Integer> starts = new ArrayList<>();
        starts.add(0);
        for (int index = 0; index < text.length(); index++) {
            if (text.charAt(index) == '\n') {
                starts.add(index + 1);
            }
        }
        return starts.stream().mapToInt(Integer::intValue).toArray();
    }

    private record Edit(int startInclusive, int endExclusive, String replacement) {
    }
}
