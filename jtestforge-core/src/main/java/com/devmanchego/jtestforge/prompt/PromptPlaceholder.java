package com.devmanchego.jtestforge.prompt;

import java.util.Arrays;
import java.util.Optional;

/**
 * The complete placeholder vocabulary — jtestforge-specification.md §6.1.
 *
 * <p>Being a closed enum rather than an open map is the point: a template referencing
 * anything outside this list is a typo, and a typo must fail at startup rather than
 * render {@code {{TARGT_METHOD}}} literally into a prompt where the model would silently
 * work around it and the mistake would never surface.
 */
public enum PromptPlaceholder {

    CLASS_FQN,
    CLASS_SOURCE,
    TARGET_METHOD,
    TARGET_METHOD_SOURCE,
    COLLABORATORS,
    EXISTING_TEST_CLASS,
    EXISTING_TEST_NAMES,
    FRAMEWORK_VERSIONS,
    UNCOVERED_LINES,
    UNCOVERED_BRANCHES,
    BEHAVIOUR_GAPS,
    COMPILER_ERRORS,
    TEST_FAILURES,
    RULES,
    TIER,
    SPRING_CONTEXT,
    SPRING_STEREOTYPE,
    REQUEST_MAPPINGS,
    VALIDATION_CONSTRAINTS,
    SECURITY_ANNOTATIONS,
    EXCEPTION_HANDLERS,
    MOCK_BEANS,
    PERSISTENCE_MODEL,
    FRAMEWORK_SEMANTIC_GAPS,
    SPRING_RULES;

    /** The token as it appears in a template, e.g. {@code {{CLASS_FQN}}}. */
    public String token() {
        return "{{" + name() + "}}";
    }

    public static Optional<PromptPlaceholder> byName(String name) {
        return Arrays.stream(values()).filter(p -> p.name().equals(name)).findFirst();
    }

    /**
     * Whether this placeholder's value is typically large enough to be worth truncating
     * when a rendered prompt exceeds {@code context.maxPromptChars} — see
     * {@link PromptRenderer}. Everything else is small enough that shrinking it would
     * save nothing while losing information the model needs.
     */
    public boolean isBulky() {
        return this == CLASS_SOURCE || this == EXISTING_TEST_CLASS || this == TARGET_METHOD_SOURCE;
    }
}
