package com.devmanchego.jtestforge.prompt;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One loaded, validated prompt template — jtestforge-specification.md §6.1.
 *
 * <p>Validated at construction: every {@code {{TOKEN}}} it contains is a known
 * {@link PromptPlaceholder}, or the template is rejected.
 */
public final class PromptTemplate {

    // Written with character classes rather than escaped braces: the sequence \{ followed
    // by { is lexed by javac as the start of a string template (a Java 21 preview
    // feature) and fails to compile even inside an ordinary string literal.
    private static final Pattern TOKEN = Pattern.compile("[{][{]([A-Za-z0-9_]+)[}][}]");

    private final PromptTemplateId id;
    private final String rawText;
    private final Set<PromptPlaceholder> referencedPlaceholders;

    public PromptTemplate(PromptTemplateId id, String rawText, String sourceDescription) {
        this.id = Objects.requireNonNull(id, "id");
        this.rawText = Objects.requireNonNull(rawText, "rawText");
        this.referencedPlaceholders = validateAndCollect(rawText, sourceDescription);
    }

    public PromptTemplateId id() {
        return id;
    }

    public String rawText() {
        return rawText;
    }

    public Set<PromptPlaceholder> referencedPlaceholders() {
        return referencedPlaceholders;
    }

    public boolean references(PromptPlaceholder placeholder) {
        return referencedPlaceholders.contains(placeholder);
    }

    private static Set<PromptPlaceholder> validateAndCollect(String text, String sourceDescription) {
        Set<PromptPlaceholder> found = new LinkedHashSet<>();
        Matcher matcher = TOKEN.matcher(text);
        while (matcher.find()) {
            String name = matcher.group(1);
            PromptPlaceholder placeholder = PromptPlaceholder.byName(name).orElseThrow(() ->
                    new TemplateValidationException(
                            "Prompt template " + sourceDescription + " references unknown placeholder {{"
                                    + name + "}}. Known placeholders: "
                                    + java.util.Arrays.toString(PromptPlaceholder.values())));
            found.add(placeholder);
        }
        return Set.copyOf(found);
    }
}
