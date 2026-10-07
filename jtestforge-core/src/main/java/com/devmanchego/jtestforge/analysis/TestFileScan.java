package com.devmanchego.jtestforge.analysis;

import com.devmanchego.jtestforge.model.TestClassInfo;

import java.util.Optional;

/**
 * What {@link TestClassScanner#inspect} found at a test file path - three answers, not two.
 *
 * <p>"No information" used to mean both "there is no file" and "there is a file I could not
 * read or parse", and every caller read it as the first. The second case is the one that
 * matters: a test class JavaParser cannot handle (a syntax it does not support, an encoding it
 * cannot decode) was then treated as missing, and the generation loop wrote a fresh skeleton
 * over it - silently deleting every test the developer had in that file.
 */
public sealed interface TestFileScan {

    /** No file at the path, or a file with nothing in it but whitespace - safe to create. */
    record Absent() implements TestFileScan {
    }

    /** A test class that was read and understood. */
    record Parsed(TestClassInfo info) implements TestFileScan {
    }

    /**
     * A file that exists and has content, but could not be read or parsed. It must never be
     * written to: whatever is in it belongs to the developer and cannot be merged safely.
     */
    record Unreadable(String reason) implements TestFileScan {
    }

    /** The class info when {@link Parsed}, empty for the other two outcomes. */
    default Optional<TestClassInfo> parsedInfo() {
        return this instanceof Parsed parsed ? Optional.of(parsed.info()) : Optional.empty();
    }
}
