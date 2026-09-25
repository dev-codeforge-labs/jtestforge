package com.devmanchego.jtestforge.config;

/**
 * Root of {@code jtestforge.yaml} — jtestforge-specification.md §5.
 *
 * <p>Every nested block defaults to a fully-defaulted instance of its own type when
 * absent from the YAML, so a config file only needs to state the values it wants to
 * override. Each nested record applies its own defaults in its compact constructor,
 * so defaulting is recursive: omitting the entire {@code spring:} key, for instance,
 * yields the same result as writing every one of its defaults out explicitly.
 */
public record JTestForgeConfig(
        ProjectConfig project,
        SelectionConfig selection,
        AiProviderConfig aiProvider,
        PromptsConfig prompts,
        SpringConfig spring,
        ContextConfig context,
        GenerateConfig generate,
        HardenConfig harden,
        ExecutionConfig execution) {

    public JTestForgeConfig {
        project = project == null
                ? new ProjectConfig(null, null, null, null, null, null, null, null, null, null, null)
                : project;
        selection = selection == null
                ? new SelectionConfig(null, null, null, null, null, null, null)
                : selection;
        aiProvider = aiProvider == null ? new AiProviderConfig(null, null, null) : aiProvider;
        prompts = prompts == null
                ? new PromptsConfig(null, null, null, null, null, null, null, null, null, null, null)
                : prompts;
        spring = spring == null
                ? new SpringConfig(null, null, null, null, null, null, null, null, null, null, null)
                : spring;
        context = context == null
                ? new ContextConfig(null, null, null, null, null, null, null)
                : context;
        generate = generate == null ? new GenerateConfig(null, null, null, null, null) : generate;
        harden = harden == null
                ? new HardenConfig(null, null, null, null, null, null, null, null, null)
                : harden;
        execution = execution == null ? new ExecutionConfig(null, null, null, null, null) : execution;
    }
}
