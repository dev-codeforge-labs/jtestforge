package com.devmanchego.jtestforge.provider;

import java.util.OptionalLong;

/**
 * How the two Ollama clients - {@link HttpAiProvider} and {@link HttpProviderPreflight} - read the
 * same configuration. One copy, so the preflight can never approve a setting the provider reads
 * differently.
 */
final class OllamaSettings {

    private OllamaSettings() {
    }

    /** An option given as a number or a numeric string (YAML users write both); empty otherwise. */
    static OptionalLong number(Object value) {
        if (value instanceof Number number) {
            return OptionalLong.of(number.longValue());
        }
        if (value instanceof String text) {
            try {
                return OptionalLong.of(Long.parseLong(text.strip()));
            } catch (NumberFormatException e) {
                return OptionalLong.empty();
            }
        }
        return OptionalLong.empty();
    }

    /** {@code baseUrl} without surrounding blanks or trailing slashes: {@code http://host:11434/} becomes {@code http://host:11434}. */
    static String root(String baseUrl) {
        String trimmed = baseUrl.strip();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }
}
