package com.devmanchego.jtestforge.prompt;

import com.devmanchego.jtestforge.config.PromptsConfig;
import com.devmanchego.jtestforge.model.Tier;

import java.util.Optional;
import java.util.function.Function;

/**
 * The eleven prompt files — jtestforge-specification.md §5's {@code prompts} block.
 *
 * <p>{@link #RULES} and {@link #SPRING_RULES} are shared blocks rather than standalone
 * prompts: they are rendered into every other template through {@code {{RULES}}} /
 * {@code {{SPRING_RULES}}} and are never sent to a model on their own.
 */
public enum PromptTemplateId {

    NEW_TEST_CLASS("prompts/new-test-class.md", PromptsConfig::newTestClass),
    ADDITIONAL_TESTS("prompts/additional-tests.md", PromptsConfig::additionalTests),
    KILL_MUTANTS("prompts/kill-mutants.md", PromptsConfig::killMutants),
    FIX_COMPILATION("prompts/fix-compilation.md", PromptsConfig::fixCompilation),
    FIX_ASSERTION("prompts/fix-assertion.md", PromptsConfig::fixAssertion),
    WEB_SLICE_TESTS("prompts/spring-web-slice.md", PromptsConfig::webSliceTests),
    DATA_SLICE_TESTS("prompts/spring-data-slice.md", PromptsConfig::dataSliceTests),
    JSON_SLICE_TESTS("prompts/spring-json-slice.md", PromptsConfig::jsonSliceTests),
    CONTEXT_TESTS("prompts/spring-context.md", PromptsConfig::contextTests),
    RULES("prompts/rules.md", PromptsConfig::rules),
    SPRING_RULES("prompts/spring-rules.md", PromptsConfig::springRules);

    private final String bundledResourcePath;
    private final Function<PromptsConfig, String> configuredPath;

    PromptTemplateId(String bundledResourcePath, Function<PromptsConfig, String> configuredPath) {
        this.bundledResourcePath = bundledResourcePath;
        this.configuredPath = configuredPath;
    }

    /** Where the default version of this template lives inside the jar. */
    public String bundledResourcePath() {
        return bundledResourcePath;
    }

    /** The path the user's own config points at, once `jtestforge init` has scaffolded it. */
    public String configuredPath(PromptsConfig prompts) {
        return configuredPath.apply(prompts);
    }

    /** The generation template for a Spring tier, or empty for {@code PLAIN_UNIT}. */
    public static Optional<PromptTemplateId> forSpringTier(Tier tier) {
        return switch (tier) {
            case WEB_SLICE -> Optional.of(WEB_SLICE_TESTS);
            case DATA_SLICE -> Optional.of(DATA_SLICE_TESTS);
            case JSON_SLICE -> Optional.of(JSON_SLICE_TESTS);
            case CONTEXT_SLICE -> Optional.of(CONTEXT_TESTS);
            case PLAIN_UNIT -> Optional.empty();
        };
    }

    /** Whether this file is a shared block rendered into other templates. */
    public boolean isSharedRulesBlock() {
        return this == RULES || this == SPRING_RULES;
    }
}
