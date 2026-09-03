package com.devmanchego.jtestforge.provider;

/**
 * Why an AI response could not be used at all — jtestforge-specification.md §6.2.
 *
 * <p>Deliberately small: only shapes that make the <em>whole</em> response unusable are
 * here. A single bad declaration inside an otherwise-good response (a stray field, a
 * method missing {@code @Test}, a duplicate name, a disallowed wildcard import) is never
 * a {@link ContractViolationKind} - those are dropped individually (§6.2 rule 5) and the
 * rest of the response is still used. Conflating the two would throw away good candidates
 * over one bad one, and would trigger the corrective re-prompt far more often than the
 * single retry budget (§9.4) can afford.
 */
public enum ContractViolationKind {
    /** No usable {@code java} fenced block was found at all (missing, empty, or truncated). */
    NOT_FENCED,
    /**
     * The {@code java} block parsed as a full compilation unit or a class/interface
     * declaration instead of a list of body declarations (§6.2 rule 2).
     */
    JAVA_BLOCK_NOT_BODY_DECLARATIONS,
    /** The {@code java} block's content is not valid Java at all. */
    UNPARSEABLE_JAVA_BLOCK
}
