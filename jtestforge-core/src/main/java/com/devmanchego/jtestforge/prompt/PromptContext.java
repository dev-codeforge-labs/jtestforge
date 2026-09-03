package com.devmanchego.jtestforge.prompt;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

/**
 * The values one prompt render will substitute — jtestforge-specification.md §6.1.
 *
 * <p>Assembled by {@code ContextAssembler}, consumed by {@link PromptRenderer}. A
 * placeholder a template references but this context does not supply is a rendering
 * error, not a silent blank: a prompt missing its target method's source is far worse
 * than a run that stops and says so.
 */
public final class PromptContext {

    private final Map<PromptPlaceholder, String> values;

    private PromptContext(Map<PromptPlaceholder, String> values) {
        this.values = values;
    }

    public static Builder builder() {
        return new Builder();
    }

    public Optional<String> get(PromptPlaceholder placeholder) {
        return Optional.ofNullable(values.get(placeholder));
    }

    public Map<PromptPlaceholder, String> values() {
        return Map.copyOf(values);
    }

    /** A copy with one value replaced - used by truncation, which never mutates in place. */
    public PromptContext with(PromptPlaceholder placeholder, String value) {
        Map<PromptPlaceholder, String> copy = new EnumMap<>(values);
        copy.put(placeholder, value);
        return new PromptContext(copy);
    }

    public static final class Builder {
        private final Map<PromptPlaceholder, String> values = new EnumMap<>(PromptPlaceholder.class);

        public Builder with(PromptPlaceholder placeholder, String value) {
            values.put(placeholder, value == null ? "" : value);
            return this;
        }

        public PromptContext build() {
            return new PromptContext(new EnumMap<>(values));
        }
    }
}
