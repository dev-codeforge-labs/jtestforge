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

    // --- fix-contract.md: added after projects were already scaffolded ------------------------

    @Test
    void aProjectScaffoldedBeforeFixContractExistedStillLoadsUsingTheBundledDefault(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws java.io.IOException {
        // Exactly what an existing project looks like after upgrading: every template it was
        // given by 'init', and no fix-contract.md.
        scaffoldAllBut(dir, PromptTemplateId.FIX_CONTRACT);

        Map<PromptTemplateId, PromptTemplate> templates = loader.load(defaultPrompts(), dir);

        assertThat(templates.get(PromptTemplateId.FIX_CONTRACT).rawText())
                .isEqualTo(loader.bundledTextOf(PromptTemplateId.FIX_CONTRACT));
    }

    @Test
    void aCustomisedFixContractFileIsUsedWhenItExists(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws java.io.IOException {
        scaffoldAllBut(dir, PromptTemplateId.FIX_CONTRACT);
        java.nio.file.Files.writeString(dir.resolve("prompts/fix-contract.md"), "My own correction. {{RULES}}");

        assertThat(loader.load(defaultPrompts(), dir).get(PromptTemplateId.FIX_CONTRACT).rawText())
                .isEqualTo("My own correction. {{RULES}}");
    }

    @Test
    void anyOtherMissingTemplateStillStopsStartupBecauseItIsAlmostCertainlyATypo(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws java.io.IOException {
        scaffoldAllBut(dir, PromptTemplateId.FIX_COMPILATION);

        assertThatThrownBy(() -> loader.load(defaultPrompts(), dir))
                .isInstanceOf(TemplateValidationException.class)
                .hasMessageContaining("fix-compilation.md");
    }

    @Test
    void theFixContractTemplateShowsThePreviousAnswerAndRestatesTheFormat() {
        PromptTemplate template = loader.loadBundled().get(PromptTemplateId.FIX_CONTRACT);

        assertThat(template.references(PromptPlaceholder.CONTRACT_VIOLATION)).isTrue();
        assertThat(template.references(PromptPlaceholder.PREVIOUS_RESPONSE)).isTrue();
        // The previous answer has its own ``` blocks; only a longer fence can hold it.
        assertThat(template.rawText()).contains("````text\n{{PREVIOUS_RESPONSE}}\n````");
    }

    private void scaffoldAllBut(java.nio.file.Path dir, PromptTemplateId missing) throws java.io.IOException {
        for (PromptTemplateId id : PromptTemplateId.values()) {
            if (id == missing) {
                continue;
            }
            java.nio.file.Path file = dir.resolve(id.configuredPath(defaultPrompts()));
            java.nio.file.Files.createDirectories(file.getParent());
            java.nio.file.Files.writeString(file, loader.bundledTextOf(id));
        }
    }

    private static com.devmanchego.jtestforge.config.PromptsConfig defaultPrompts() {
        return new com.devmanchego.jtestforge.config.PromptsConfig(
                null, null, null, null, null, null, null, null, null, null, null, null);
    }
}
