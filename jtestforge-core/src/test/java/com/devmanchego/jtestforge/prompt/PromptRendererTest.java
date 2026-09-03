package com.devmanchego.jtestforge.prompt;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PromptRendererTest {

    private final PromptRenderer renderer = new PromptRenderer(60_000);

    @Test
    void substitutesEveryReferencedPlaceholder() {
        PromptTemplate template = template("Testing {{CLASS_FQN}} method {{TARGET_METHOD}}.");
        PromptContext context = PromptContext.builder()
                .with(PromptPlaceholder.CLASS_FQN, "com.acme.PaymentService")
                .with(PromptPlaceholder.TARGET_METHOD, "applyFee(BigDecimal)")
                .build();

        assertThat(renderer.render(template, context))
                .isEqualTo("Testing com.acme.PaymentService method applyFee(BigDecimal).");
    }

    @Test
    void substitutesEveryOccurrenceOfARepeatedPlaceholder() {
        PromptTemplate template = template("{{CLASS_FQN}} then {{CLASS_FQN}}");
        PromptContext context = PromptContext.builder()
                .with(PromptPlaceholder.CLASS_FQN, "com.acme.Foo").build();

        assertThat(renderer.render(template, context)).isEqualTo("com.acme.Foo then com.acme.Foo");
    }

    @Test
    void aValueContainingRegexReplacementSyntaxIsSubstitutedLiterally() {
        // Java source routinely contains $ and \ - naive String.replaceAll would treat
        // them as group references and either corrupt the source or throw.
        PromptTemplate template = template("Source:\n{{CLASS_SOURCE}}");
        PromptContext context = PromptContext.builder()
                .with(PromptPlaceholder.CLASS_SOURCE,
                        "var x = \"$1 and \\\\ backslash\"; // Outer$Inner")
                .build();

        assertThat(renderer.render(template, context))
                .contains("var x = \"$1 and \\\\ backslash\"; // Outer$Inner");
    }

    @Test
    void aMissingValueForAReferencedPlaceholderIsAnErrorNotASilentBlank() {
        // A prompt missing its target method's source is far worse than a run that stops
        // and says so: the model would answer confidently about nothing in particular.
        PromptTemplate template = template("Testing {{CLASS_FQN}}");

        assertThatThrownBy(() -> renderer.render(template, PromptContext.builder().build()))
                .isInstanceOf(TemplateValidationException.class)
                .hasMessageContaining("CLASS_FQN");
    }

    @Test
    void aPromptWithinBudgetIsLeftExactlyAsRendered() {
        PromptRenderer small = new PromptRenderer(1000);
        PromptTemplate template = template("{{CLASS_SOURCE}}");
        String source = "x".repeat(500);

        assertThat(small.render(template, contextWithSource(source))).isEqualTo(source);
    }

    @Test
    void anOversizedPromptShrinksItsBulkyValueAndLeavesTheInstructionsIntact() {
        String instructions = "IMPORTANT INSTRUCTIONS THAT MUST SURVIVE";
        PromptRenderer limited = new PromptRenderer(2000);
        PromptTemplate template = template("{{CLASS_SOURCE}}\n" + instructions);

        String rendered = limited.render(template, contextWithSource("x".repeat(10_000)));

        assertThat(rendered.length()).isLessThanOrEqualTo(2000);
        assertThat(rendered).endsWith(instructions);
    }

    @Test
    void truncationLeavesAnExplicitMarkerSoTheModelKnowsItHasAFragment() {
        PromptRenderer limited = new PromptRenderer(2000);
        PromptTemplate template = template("{{CLASS_SOURCE}}");

        String rendered = limited.render(template, contextWithSource("x".repeat(10_000)));

        assertThat(rendered).contains("truncated by JTestForge");
        assertThat(rendered).contains("characters omitted");
    }

    @Test
    void truncationKeepsBothEndsOfTheValueRatherThanOnlyTheHead() {
        // A class's head (package, imports, fields) and its tail are both far more useful
        // than its middle; cutting only the tail would routinely lose the very method the
        // prompt is about.
        PromptRenderer limited = new PromptRenderer(1200);
        PromptTemplate template = template("{{CLASS_SOURCE}}");
        String source = "HEAD_MARKER" + "x".repeat(10_000) + "TAIL_MARKER";

        String rendered = limited.render(template, contextWithSource(source));

        assertThat(rendered).contains("HEAD_MARKER");
        assertThat(rendered).contains("TAIL_MARKER");
    }

    @Test
    void aSmallValueIsNeverTruncatedEvenWhenTheBudgetIsExceeded() {
        // Shrinking a 20-character value cannot bring a 5000-character prompt under
        // budget; mangling it would lose information for no gain.
        PromptRenderer tiny = new PromptRenderer(10);
        PromptTemplate template = template("{{CLASS_FQN}}");
        PromptContext context = PromptContext.builder()
                .with(PromptPlaceholder.CLASS_FQN, "com.acme.AVeryLongClassNameIndeed").build();

        assertThat(tiny.render(template, context)).isEqualTo("com.acme.AVeryLongClassNameIndeed");
    }

    @Test
    void capValueTruncatesToTheRequestedCeiling() {
        String capped = PromptRenderer.capValue("y".repeat(5000), 1000);

        assertThat(capped.length()).isLessThan(5000);
        assertThat(capped).contains("truncated by JTestForge");
    }

    @Test
    void capValueLeavesAValueAlreadyWithinItsCapUntouched() {
        assertThat(PromptRenderer.capValue("short", 1000)).isEqualTo("short");
        assertThat(PromptRenderer.capValue(null, 1000)).isNull();
    }

    private PromptTemplate template(String text) {
        return new PromptTemplate(PromptTemplateId.ADDITIONAL_TESTS, text, "test-source");
    }

    private PromptContext contextWithSource(String source) {
        return PromptContext.builder().with(PromptPlaceholder.CLASS_SOURCE, source).build();
    }
}
