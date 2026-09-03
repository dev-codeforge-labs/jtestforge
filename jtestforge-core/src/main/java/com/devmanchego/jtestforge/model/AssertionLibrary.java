package com.devmanchego.jtestforge.model;

/**
 * Which assertion library an existing test class already uses.
 *
 * <p>Detected rather than dictated: jtestforge-specification.md §7.2 requires new tests
 * to match the file's existing style, so this is passed to the model as context. A
 * generated AssertJ assertion dropped into a JUnit-assertion file is not wrong, but it
 * makes the file inconsistent in a way a reviewer will hold against the tool.
 */
public enum AssertionLibrary {
    /** {@code org.junit.jupiter.api.Assertions}. */
    JUNIT,
    /** {@code org.assertj.core.api.Assertions}. */
    ASSERTJ,
    /** {@code org.hamcrest} matchers. */
    HAMCREST,
    /** More than one of the above - new tests should follow the dominant local style. */
    MIXED,
    /** No assertions at all, typically an empty or freshly created class. */
    NONE
}
