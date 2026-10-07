package com.devmanchego.jtestforge.config;

/**
 * {@code prompts} block — jtestforge-specification.md §5, §6.1. Every path is relative
 * to the directory containing {@code jtestforge.yaml} unless absolute.
 */
public record PromptsConfig(
        String newTestClass,
        String additionalTests,
        String killMutants,
        String fixCompilation,
        String fixAssertion,
        String webSliceTests,
        String dataSliceTests,
        String jsonSliceTests,
        String contextTests,
        String rules,
        String springRules,
        String fixContract) {

    public PromptsConfig {
        newTestClass = defaultIfBlank(newTestClass, "prompts/new-test-class.md");
        additionalTests = defaultIfBlank(additionalTests, "prompts/additional-tests.md");
        killMutants = defaultIfBlank(killMutants, "prompts/kill-mutants.md");
        fixCompilation = defaultIfBlank(fixCompilation, "prompts/fix-compilation.md");
        fixAssertion = defaultIfBlank(fixAssertion, "prompts/fix-assertion.md");
        webSliceTests = defaultIfBlank(webSliceTests, "prompts/spring-web-slice.md");
        dataSliceTests = defaultIfBlank(dataSliceTests, "prompts/spring-data-slice.md");
        jsonSliceTests = defaultIfBlank(jsonSliceTests, "prompts/spring-json-slice.md");
        contextTests = defaultIfBlank(contextTests, "prompts/spring-context.md");
        rules = defaultIfBlank(rules, "prompts/rules.md");
        springRules = defaultIfBlank(springRules, "prompts/spring-rules.md");
        fixContract = defaultIfBlank(fixContract, "prompts/fix-contract.md");
    }

    /** The shape this record had before {@code fixContract} existed; leaves it at its default. */
    public PromptsConfig(String newTestClass, String additionalTests, String killMutants, String fixCompilation,
                         String fixAssertion, String webSliceTests, String dataSliceTests, String jsonSliceTests,
                         String contextTests, String rules, String springRules) {
        this(newTestClass, additionalTests, killMutants, fixCompilation, fixAssertion, webSliceTests,
                dataSliceTests, jsonSliceTests, contextTests, rules, springRules, null);
    }

    private static String defaultIfBlank(String value, String fallback) {
        return (value == null || value.isBlank()) ? fallback : value;
    }
}
