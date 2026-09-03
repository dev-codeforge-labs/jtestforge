package com.devmanchego.jtestforge.prompt;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Substitutes a {@link PromptContext} into a {@link PromptTemplate} —
 * jtestforge-specification.md §6.1: "Rendering is literal substitution — no expression
 * language, no loops."
 *
 * <p>When the result exceeds {@code context.maxPromptChars}, the <b>values</b> are shrunk
 * and the prompt re-rendered - never the rendered text itself. Truncating the rendered
 * output would cut off whatever came last, which in every template is the rules block and
 * the response-format contract: the model would then answer in a shape the parser rejects,
 * and the run would burn its repair budget on a problem JTestForge itself created.
 *
 * <p>Only bulky values are candidates ({@link PromptPlaceholder#isBulky()}), largest
 * first, and each truncation leaves an explicit marker so the model can see that it is
 * looking at a fragment rather than a whole file.
 */
public final class PromptRenderer {

    private static final String TRUNCATION_MARKER_FORMAT =
            "%n… [truncated by JTestForge: %d characters omitted] …%n";
    /** Below this, shrinking a value costs more information than the characters it saves. */
    private static final int MINIMUM_USEFUL_VALUE_LENGTH = 200;

    private final int maxPromptChars;

    public PromptRenderer(int maxPromptChars) {
        this.maxPromptChars = maxPromptChars;
    }

    public String render(PromptTemplate template, PromptContext context) {
        PromptContext current = context;
        String rendered = substitute(template, current);

        while (rendered.length() > maxPromptChars) {
            Optional<PromptPlaceholder> victim = largestShrinkableValue(template, current);
            if (victim.isEmpty()) {
                // Nothing left worth shrinking: the template's own fixed text plus its
                // small values already exceed the budget. Returning the oversized prompt
                // is better than returning a mutilated one - the provider, or the model,
                // will say so plainly, whereas a silently gutted prompt would produce
                // quietly worse tests with nothing to attribute them to.
                return rendered;
            }
            int excess = rendered.length() - maxPromptChars;
            current = current.with(victim.get(),
                    truncate(current.get(victim.get()).orElse(""), excess));
            rendered = substitute(template, current);
        }
        return rendered;
    }

    /** Applies a per-value cap before rendering, e.g. {@code context.maxExistingTestChars}. */
    public static String capValue(String value, int maxChars) {
        if (value == null || value.length() <= maxChars) {
            return value;
        }
        return truncate(value, value.length() - maxChars);
    }

    private String substitute(PromptTemplate template, PromptContext context) {
        String result = template.rawText();
        for (PromptPlaceholder placeholder : template.referencedPlaceholders()) {
            String value = context.get(placeholder).orElseThrow(() ->
                    new TemplateValidationException(
                            "Template " + template.id() + " references " + placeholder.token()
                                    + " but the assembled context supplies no value for it."));
            // String.replace is already a literal replacement - unlike replaceAll, it
            // treats neither the target nor the replacement as a regex. Passing the value
            // through Matcher.quoteReplacement here would inject literal backslashes into
            // any Java source containing '$' or '\', corrupting the very code the model is
            // being asked to read.
            result = result.replace(placeholder.token(), value);
        }
        return result;
    }

    private Optional<PromptPlaceholder> largestShrinkableValue(PromptTemplate template, PromptContext context) {
        return template.referencedPlaceholders().stream()
                .filter(PromptPlaceholder::isBulky)
                .filter(placeholder -> context.get(placeholder)
                        .map(value -> value.length() > MINIMUM_USEFUL_VALUE_LENGTH)
                        .orElse(false))
                .max(Comparator.comparingInt(placeholder -> context.get(placeholder).orElse("").length()));
    }

    /**
     * Removes {@code charactersToRemove} from the middle, keeping both ends. A source
     * file's head (package, imports, field declarations) and its tail are both far more
     * useful to the model than its middle, and cutting only the tail would routinely lose
     * the very method the prompt is about.
     */
    private static String truncate(String value, int charactersToRemove) {
        int marker = String.format(TRUNCATION_MARKER_FORMAT, charactersToRemove).length();
        int toRemove = Math.min(charactersToRemove + marker, value.length() - MINIMUM_USEFUL_VALUE_LENGTH);
        if (toRemove <= 0) {
            return value;
        }
        int keepEachSide = (value.length() - toRemove) / 2;
        return value.substring(0, keepEachSide)
                + String.format(TRUNCATION_MARKER_FORMAT, toRemove)
                + value.substring(value.length() - keepEachSide);
    }
}
