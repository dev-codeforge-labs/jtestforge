package com.devmanchego.jtestforge.model;

import java.util.Objects;

/**
 * One piece of framework-mediated behaviour that no current test verifies —
 * jtestforge-specification.md §7.5.
 *
 * <p>{@code description} is a <b>behavioural statement</b>, never an annotation dump. The
 * model is told "an unauthenticated request to this endpoint must be rejected and no
 * current test proves it", never "add an assertion for the {@code @PreAuthorize}
 * annotation on line 31". The same discipline as the mutant translation in §10.2, and for
 * the same reason: instructing at the annotation level produces tests coupled to the
 * implementation rather than to the contract.
 *
 * @param kind          which family of framework behaviour this is
 * @param className     the production class carrying the signal
 * @param methodName    the method carrying it, or {@code null} for a class-level gap
 * @param description   the behavioural statement rendered into {@code {{FRAMEWORK_SEMANTIC_GAPS}}}
 * @param httpMethod    for web gaps, the HTTP method, e.g. {@code GET}; otherwise {@code null}
 * @param path          for web gaps, the mapped path; otherwise {@code null}
 * @param sourceLine    line of the signal, supplied as location context only
 */
public record SemanticGap(
        SemanticGapKind kind,
        String className,
        String methodName,
        String description,
        String httpMethod,
        String path,
        int sourceLine) {

    public SemanticGap {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(className, "className");
        Objects.requireNonNull(description, "description");
    }

    public Tier closingTier() {
        return kind.closingTier();
    }

    public AssertionShape requiredShape() {
        return kind.requiredShape();
    }

    /**
     * Stable identity for state and report bookkeeping. Excludes the line number on
     * purpose: an unchanged gap must not look like a new one merely because unrelated
     * edits moved the class around, exactly as mutant identity avoids raw line numbers
     * (§14 of the implementation plan).
     */
    public String id() {
        return kind.name() + "@" + className + (methodName == null ? "" : "#" + methodName)
                + (path == null ? "" : "[" + httpMethod + " " + path + "]");
    }
}
