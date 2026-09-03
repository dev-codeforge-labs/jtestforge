package com.devmanchego.jtestforge.model;

/**
 * How an existing test class obtains the subject under test —
 * jtestforge-specification.md §7.2.
 *
 * <p>Recorded so generated tests follow the file's existing arrangement instead of
 * introducing a second, competing one: a test that constructs its own subject inside a
 * class already using {@code @InjectMocks} silently bypasses every configured mock.
 */
public enum InjectionStyle {
    /** Mockito's {@code @InjectMocks} field. */
    INJECT_MOCKS,
    /** The subject is constructed by hand, usually in {@code @BeforeEach}. */
    MANUAL_CONSTRUCTION,
    /** A Spring slice: collaborators are mock beans and the subject comes from the context. */
    SPRING_MOCK_BEANS,
    /** No subject is set up at all. */
    NONE
}
