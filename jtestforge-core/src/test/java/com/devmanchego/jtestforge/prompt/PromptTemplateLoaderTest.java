package com.devmanchego.jtestforge.prompt;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PromptTemplateLoaderTest {

    private final PromptTemplateLoader loader = new PromptTemplateLoader();

    @Test
    void everyBundledTemplateLoadsAndValidates() {
        Map<PromptTemplateId, PromptTemplate> templates = loader.loadBundled();

        assertThat(templates).hasSize(PromptTemplateId.values().length);
        assertThat(templates.keySet()).containsExactlyInAnyOrder(PromptTemplateId.values());
    }

    @Test
    void noBundledTemplateIsEmpty() {
        loader.loadBundled().forEach((id, template) ->
                assertThat(template.rawText()).as("%s", id).isNotBlank());
    }

    @Test
    void everyGenerationTemplateIncludesTheSharedRulesBlock() {
        // The rules block carries the response-format contract (§6.2). A generation
        // template that omitted it would produce answers the parser rejects every time.
        Map<PromptTemplateId, PromptTemplate> templates = loader.loadBundled();

        for (PromptTemplateId id : PromptTemplateId.values()) {
            if (id.isSharedRulesBlock()) {
                continue;
            }
            assertThat(templates.get(id).references(PromptPlaceholder.RULES))
                    .as("%s must include {{RULES}}", id).isTrue();
        }
    }

    @Test
    void everySpringTierTemplateIncludesTheSpringRulesBlock() {
        Map<PromptTemplateId, PromptTemplate> templates = loader.loadBundled();

        for (PromptTemplateId id : java.util.List.of(PromptTemplateId.WEB_SLICE_TESTS,
                PromptTemplateId.DATA_SLICE_TESTS, PromptTemplateId.JSON_SLICE_TESTS,
                PromptTemplateId.CONTEXT_TESTS)) {
            assertThat(templates.get(id).references(PromptPlaceholder.SPRING_RULES))
                    .as("%s must include {{SPRING_RULES}}", id).isTrue();
        }
    }

    @Test
    void theSharedRulesBlocksThemselvesReferenceNoPlaceholders() {
        // They are substituted INTO other templates; a placeholder inside one would be
        // rendered after its host had already been rendered, and would survive as a
        // literal token in the final prompt.
        Map<PromptTemplateId, PromptTemplate> templates = loader.loadBundled();

        assertThat(templates.get(PromptTemplateId.RULES).referencedPlaceholders()).isEmpty();
        assertThat(templates.get(PromptTemplateId.SPRING_RULES).referencedPlaceholders()).isEmpty();
    }

    @Test
    void anUnknownPlaceholderIsRejectedAtLoadTimeWithBothNames() {
        assertThatThrownBy(() -> new PromptTemplate(PromptTemplateId.RULES,
                "Write tests for {{TARGT_METHOD}} please.", "test-source"))
                .isInstanceOf(TemplateValidationException.class)
                .hasMessageContaining("TARGT_METHOD")
                .hasMessageContaining("test-source");
    }

    @Test
    void aTemplateWithNoPlaceholdersIsPerfectlyValid() {
        PromptTemplate template = new PromptTemplate(
                PromptTemplateId.RULES, "Just some fixed instructions.", "test-source");

        assertThat(template.referencedPlaceholders()).isEmpty();
    }

    @Test
    void placeholdersAreCollectedWithoutDuplicates() {
        PromptTemplate template = new PromptTemplate(PromptTemplateId.ADDITIONAL_TESTS,
                "{{CLASS_FQN}} and again {{CLASS_FQN}} plus {{RULES}}", "test-source");

        assertThat(template.referencedPlaceholders())
                .containsExactlyInAnyOrder(PromptPlaceholder.CLASS_FQN, PromptPlaceholder.RULES);
    }

    @Test
    void bundledTextIsAvailableForInitToScaffoldOut() {
        assertThat(loader.bundledTextOf(PromptTemplateId.RULES)).contains("Response format");
    }
}
