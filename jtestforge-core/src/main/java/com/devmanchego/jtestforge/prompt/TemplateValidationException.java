package com.devmanchego.jtestforge.prompt;

/**
 * Thrown at startup when a prompt template references a placeholder outside the
 * {@link PromptPlaceholder} vocabulary — jtestforge-implementation-plan.md phase 10:
 * "unknown = error".
 *
 * <p>Loud and early on purpose. An unrecognised token would otherwise be rendered
 * verbatim into the prompt, where the model would quietly work around it and the typo
 * would never be noticed - while every generation for that template silently ran with a
 * piece of its context missing.
 */
public final class TemplateValidationException extends RuntimeException {

    public TemplateValidationException(String message) {
        super(message);
    }

    public TemplateValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
