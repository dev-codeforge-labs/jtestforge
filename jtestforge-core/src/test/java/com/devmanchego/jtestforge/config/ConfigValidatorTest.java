package com.devmanchego.jtestforge.config;

import com.devmanchego.jtestforge.model.Tier;
import org.junit.jupiter.api.Test;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ConfigValidatorTest {

    private final ConfigValidator validator = new ConfigValidator();

    @Test
    void aFullyValidConfigProducesNoErrorsAndNoWarnings() throws Exception {
        JTestForgeConfig config = fullyValidConfig();
        ValidationContext context = validContext();

        ValidationResult result = validator.validate(config, context);

        assertThat(result.errors()).isEmpty();
        assertThat(result.isValid()).isTrue();
    }

    @Test
    void aMissingDependencyTreeFileAndABadJavaVersionAreReportedBeforeARun() throws Exception {
        JTestForgeConfig config = withProject(fullyValidConfig(),
                p -> new ProjectConfig(p.modulePath(), p.mavenExecutable(), p.mavenArgs(),
                        p.javaHome(), p.testSourceRoot(), p.mainSourceRoot(), p.testClassSuffix(),
                        p.testClassSuffixByTier(), "C:/no/such/deps-tree.txt", null, "eight"));

        ValidationResult result = validator.validate(config, validContext());

        assertThat(result.errors()).extracting(ConfigViolation::path)
                .contains("project.dependencyTreeFile", "project.javaVersion");
    }

    @Test
    void modulePathMustExistAndContainAPomXml() throws Exception {
        JTestForgeConfig config = withProject(fullyValidConfig(),
                p -> new ProjectConfig("C:/this/path/does/not/exist", p.mavenExecutable(), p.mavenArgs(),
                        p.javaHome(), p.testSourceRoot(), p.mainSourceRoot(), p.testClassSuffix(),
                        p.testClassSuffixByTier(), p.dependencyTreeFile(), p.localRepository(), p.javaVersion()));

        ValidationResult result = validator.validate(config, validContext());

        assertThat(result.errors()).extracting(ConfigViolation::path).contains("project.modulePath");
    }

    @Test
    void theActiveProviderCommandMustBeResolvable() throws Exception {
        JTestForgeConfig base = fullyValidConfig();
        Map<String, ProviderConfig> providers = Map.of("claude",
                new ProviderConfig("definitely-not-a-real-command-xyz", null, null, null, null, null, null));
        JTestForgeConfig config = withAiProvider(base, new AiProviderConfig("claude", providers, null));

        ValidationResult result = validator.validate(config, validContext());

        assertThat(result.errors()).extracting(ConfigViolation::path)
                .anyMatch(p -> p.startsWith("aiProvider.providers.claude.command"));
    }

    @Test
    void aProvidersMaxPromptCharsOverrideMustBePositiveWhenSet() throws Exception {
        JTestForgeConfig base = fullyValidConfig();
        Map<String, ProviderConfig> providers = Map.of("claude",
                new ProviderConfig(resolvableCommand(), null, null, null, null, null, 0));
        JTestForgeConfig config = withAiProvider(base, new AiProviderConfig("claude", providers, null));

        ValidationResult result = validator.validate(config, validContext());

        assertThat(result.errors()).extracting(ConfigViolation::path)
                .contains("aiProvider.providers.claude.maxPromptChars");
    }

    @Test
    void aProvidersMaxPromptCharsOverrideIsOptional() throws Exception {
        JTestForgeConfig base = fullyValidConfig();
        Map<String, ProviderConfig> providers = Map.of("claude",
                new ProviderConfig(resolvableCommand(), null, null, null, null, null, 8000));
        JTestForgeConfig config = withAiProvider(base, new AiProviderConfig("claude", providers, null));

        ValidationResult result = validator.validate(config, validContext());

        assertThat(result.errors()).extracting(ConfigViolation::path)
                .doesNotContain("aiProvider.providers.claude.maxPromptChars");
    }

    @Test
    void referencedPromptFilesMustExist() throws Exception {
        JTestForgeConfig base = fullyValidConfig();
        PromptsConfig withMissingRules = new PromptsConfig(
                base.prompts().newTestClass(), base.prompts().additionalTests(),
                base.prompts().killMutants(), base.prompts().fixCompilation(),
                base.prompts().fixAssertion(), base.prompts().webSliceTests(),
                base.prompts().dataSliceTests(), base.prompts().jsonSliceTests(),
                base.prompts().contextTests(), "prompts/does-not-exist.md",
                base.prompts().springRules());
        JTestForgeConfig config = withPrompts(base, withMissingRules);

        ValidationResult result = validator.validate(config, validContext());

        assertThat(result.errors()).extracting(ConfigViolation::path).contains("prompts.rules");
    }

    @Test
    void standaloneWrapperJarMustExistWhenThatEngineIsSelected() throws Exception {
        JTestForgeConfig base = fullyValidConfig();
        HardenConfig harden = base.harden();
        JTestForgeConfig config = withHarden(base, new HardenConfig(
                HardenEngine.STANDALONE_WRAPPER, "C:/tools/does-not-exist.jar",
                harden.mutators(), harden.maxMutantsPerPrompt(), harden.maxAttemptsPerMutant(),
                harden.minMutationScore(), harden.maxTierForMutation(),
                harden.unkillableMutantsFile(), harden.historyFile()));

        ValidationResult result = validator.validate(config, validContext());

        assertThat(result.errors()).extracting(ConfigViolation::path).contains("harden.standaloneWrapperJar");
    }

    @Test
    void stateDirInsideTargetIsAHardError() throws Exception {
        JTestForgeConfig base = fullyValidConfig();
        JTestForgeConfig config = withExecution(base, new ExecutionConfig(
                "target/.jtestforge", null, null, null, null));

        ValidationResult result = validator.validate(config, validContext());

        assertThat(result.errors()).extracting(ConfigViolation::path).contains("execution.stateDir");
    }

    @Test
    void timeoutsAndAttemptCountersMustBePositive() throws Exception {
        JTestForgeConfig base = fullyValidConfig();
        JTestForgeConfig config = withGenerate(base, new GenerateConfig(0, 0, null, null, null));

        ValidationResult result = validator.validate(config, validContext());

        assertThat(result.errors()).extracting(ConfigViolation::path)
                .contains("generate.maxTestsPerMethod", "generate.maxRepairAttempts");
    }

    @Test
    void springEnabledTrueWithoutSpringTestOnClasspathIsAnError() throws Exception {
        JTestForgeConfig base = fullyValidConfig();
        JTestForgeConfig config = withSpring(base, new SpringConfig(
                SpringEnabledMode.ENABLED, base.spring().tiers(), null, null, null, null, null, null, null, null, null));
        ValidationContext contextWithoutSpringTest = new ValidationContext(
                validContext().configBaseDir(), validContext().pathDirectories(), false, false);

        ValidationResult result = validator.validate(config, contextWithoutSpringTest);

        assertThat(result.errors()).extracting(ConfigViolation::path).contains("spring.enabled");
    }

    @Test
    void springEnabledAutoWithoutSpringTestOnClasspathIsALoggedDowngradeNotAnError() throws Exception {
        JTestForgeConfig base = fullyValidConfig();
        JTestForgeConfig config = withSpring(base, new SpringConfig(
                SpringEnabledMode.AUTO, base.spring().tiers(), null, null, null, null, null, null, null, null, null));
        ValidationContext contextWithoutSpringTest = new ValidationContext(
                validContext().configBaseDir(), validContext().pathDirectories(), false, false);

        ValidationResult result = validator.validate(config, contextWithoutSpringTest);

        assertThat(result.errors()).isEmpty();
        assertThat(result.warnings()).extracting(ConfigViolation::path).contains("spring.enabled");
    }

    @Test
    void everyEnabledSpringTierRequiresItsPromptTemplateToBePresent() throws Exception {
        JTestForgeConfig base = fullyValidConfig();
        PromptsConfig missingWebSlicePrompt = new PromptsConfig(
                base.prompts().newTestClass(), base.prompts().additionalTests(),
                base.prompts().killMutants(), base.prompts().fixCompilation(),
                base.prompts().fixAssertion(), "prompts/does-not-exist-web-slice.md",
                base.prompts().dataSliceTests(), base.prompts().jsonSliceTests(),
                base.prompts().contextTests(), base.prompts().rules(), base.prompts().springRules());
        JTestForgeConfig withMissingPrompt = withPrompts(base, missingWebSlicePrompt);
        JTestForgeConfig config = withSpring(withMissingPrompt, new SpringConfig(
                SpringEnabledMode.ENABLED, withMissingPrompt.spring().tiers(),
                null, null, null, null, null, null, null, null, null));

        ValidationContext contextWithSpringTest = new ValidationContext(
                validContext().configBaseDir(), validContext().pathDirectories(), true, false);

        ValidationResult result = validator.validate(config, contextWithSpringTest);

        assertThat(result.errors()).extracting(ConfigViolation::path).contains("prompts.webSliceTests");
    }

    @Test
    void maxTierForMutationMustNotBeContextSlice() throws Exception {
        JTestForgeConfig base = fullyValidConfig();
        HardenConfig harden = base.harden();
        JTestForgeConfig config = withHarden(base, new HardenConfig(
                harden.engine(), harden.standaloneWrapperJar(), harden.mutators(),
                harden.maxMutantsPerPrompt(), harden.maxAttemptsPerMutant(), harden.minMutationScore(),
                Tier.CONTEXT_SLICE, harden.unkillableMutantsFile(), harden.historyFile()));

        ValidationResult result = validator.validate(config, validContext());

        assertThat(result.errors()).extracting(ConfigViolation::path).contains("harden.maxTierForMutation");
    }

    @Test
    void maxTierForMutationMustNameATierThatIsEnabled() throws Exception {
        JTestForgeConfig base = fullyValidConfig();
        TiersConfig dataSliceDisabled = new TiersConfig(null, null, false, null, null);
        JTestForgeConfig withDisabledDataSlice = withSpring(base, new SpringConfig(
                base.spring().enabled(), dataSliceDisabled, null, null, null, null, null, null, null, null, null));
        HardenConfig harden = withDisabledDataSlice.harden();
        JTestForgeConfig config = withHarden(withDisabledDataSlice, new HardenConfig(
                harden.engine(), harden.standaloneWrapperJar(), harden.mutators(),
                harden.maxMutantsPerPrompt(), harden.maxAttemptsPerMutant(), harden.minMutationScore(),
                Tier.DATA_SLICE, harden.unkillableMutantsFile(), harden.historyFile()));

        ValidationResult result = validator.validate(config, validContext());

        assertThat(result.errors()).extracting(ConfigViolation::path).contains("harden.maxTierForMutation");
    }

    @Test
    void allowTestcontainersRequiresAReachableDockerDaemon() throws Exception {
        JTestForgeConfig base = fullyValidConfig();
        JTestForgeConfig config = withSpring(base, new SpringConfig(
                base.spring().enabled(), base.spring().tiers(), null, null, null, true, null, null, null, null, null));
        ValidationContext contextWithoutDocker = new ValidationContext(
                validContext().configBaseDir(), validContext().pathDirectories(),
                validContext().springTestOnClasspath(), false);

        ValidationResult result = validator.validate(config, contextWithoutDocker);

        assertThat(result.errors()).extracting(ConfigViolation::path).contains("spring.allowTestcontainers");
    }

    // --- Fixture construction ------------------------------------------------------

    private JTestForgeConfig fullyValidConfig() throws URISyntaxException {
        Path moduleDir = classpathResource("fixture-module");
        return new JTestForgeConfig(
                new ProjectConfig(moduleDir.toString(), null, null, null, null, null, null, null, null, null, null),
                null,
                new AiProviderConfig("claude", Map.of(
                        "claude", new ProviderConfig(resolvableCommand(), null, null, null, null, null, null)), null),
                null,
                new SpringConfig(SpringEnabledMode.DISABLED, null, null, null, null, null, null, null, null, null, null),
                null, null, null, null);
    }

    private ValidationContext validContext() throws URISyntaxException {
        Path configBaseDir = classpathResource("config");
        return new ValidationContext(configBaseDir,
                com.devmanchego.jtestforge.util.ExecutableResolver.systemPathDirectories(), false, false);
    }

    private String resolvableCommand() {
        // "java" is guaranteed resolvable in a JVM test process: it is the executable
        // that launched this very test, so it is always on PATH.
        return "java";
    }

    private Path classpathResource(String resourcePath) throws URISyntaxException {
        return Path.of(getClass().getClassLoader().getResource(resourcePath).toURI());
    }

    private JTestForgeConfig withProject(JTestForgeConfig c, java.util.function.Function<ProjectConfig, ProjectConfig> f) {
        return new JTestForgeConfig(f.apply(c.project()), c.selection(), c.aiProvider(), c.prompts(),
                c.spring(), c.context(), c.generate(), c.harden(), c.execution());
    }

    private JTestForgeConfig withAiProvider(JTestForgeConfig c, AiProviderConfig aiProvider) {
        return new JTestForgeConfig(c.project(), c.selection(), aiProvider, c.prompts(),
                c.spring(), c.context(), c.generate(), c.harden(), c.execution());
    }

    private JTestForgeConfig withPrompts(JTestForgeConfig c, PromptsConfig prompts) {
        return new JTestForgeConfig(c.project(), c.selection(), c.aiProvider(), prompts,
                c.spring(), c.context(), c.generate(), c.harden(), c.execution());
    }

    private JTestForgeConfig withSpring(JTestForgeConfig c, SpringConfig spring) {
        return new JTestForgeConfig(c.project(), c.selection(), c.aiProvider(), c.prompts(),
                spring, c.context(), c.generate(), c.harden(), c.execution());
    }

    private JTestForgeConfig withGenerate(JTestForgeConfig c, GenerateConfig generate) {
        return new JTestForgeConfig(c.project(), c.selection(), c.aiProvider(), c.prompts(),
                c.spring(), c.context(), generate, c.harden(), c.execution());
    }

    private JTestForgeConfig withHarden(JTestForgeConfig c, HardenConfig harden) {
        return new JTestForgeConfig(c.project(), c.selection(), c.aiProvider(), c.prompts(),
                c.spring(), c.context(), c.generate(), harden, c.execution());
    }

    private JTestForgeConfig withExecution(JTestForgeConfig c, ExecutionConfig execution) {
        return new JTestForgeConfig(c.project(), c.selection(), c.aiProvider(), c.prompts(),
                c.spring(), c.context(), c.generate(), c.harden(), execution);
    }

    @Test
    void anUnknownSourceEncodingIsReportedAndAKnownOneIsNot() throws Exception {
        JTestForgeConfig bad = withProject(fullyValidConfig(), p -> projectWithSourceEncoding(p, "latin-nine-and-a-half"));
        JTestForgeConfig good = withProject(fullyValidConfig(), p -> projectWithSourceEncoding(p, "windows-1252"));
        JTestForgeConfig blank = withProject(fullyValidConfig(), p -> projectWithSourceEncoding(p, "  "));

        assertThat(validator.validate(bad, validContext()).errors()).extracting(ConfigViolation::path)
                .contains("project.sourceEncoding");
        assertThat(validator.validate(good, validContext()).errors()).extracting(ConfigViolation::path)
                .doesNotContain("project.sourceEncoding");
        assertThat(validator.validate(blank, validContext()).errors()).extracting(ConfigViolation::path)
                .doesNotContain("project.sourceEncoding");
    }

    private static ProjectConfig projectWithSourceEncoding(ProjectConfig p, String encoding) {
        return new ProjectConfig(p.modulePath(), p.mavenExecutable(), p.mavenArgs(), p.javaHome(),
                p.testSourceRoot(), p.mainSourceRoot(), p.testClassSuffix(), p.testClassSuffixByTier(),
                p.dependencyTreeFile(), p.localRepository(), p.javaVersion(), encoding);
    }

    @Test
    void promptDeliveryArgumentThroughABatchLauncherIsWarnedAbout() throws Exception {
        JTestForgeConfig base = fullyValidConfig();
        JTestForgeConfig config = withActiveProvider(base, "gemini.cmd", PromptDelivery.ARGUMENT);

        ValidationResult result = validator.validate(config, validContext());

        assertThat(result.warnings()).extracting(ConfigViolation::path)
                .contains("aiProvider.providers." + base.aiProvider().active() + ".promptDelivery");
        assertThat(result.warnings()).anySatisfy(warning ->
                assertThat(warning.toString()).contains("cmd.exe").contains("stdin"));
    }

    @Test
    void stdinDeliveryThroughABatchLauncherIsNotWarnedAbout() throws Exception {
        JTestForgeConfig config = withActiveProvider(fullyValidConfig(), "gemini.cmd", PromptDelivery.STDIN);

        assertThat(validator.validate(config, validContext()).warnings()).extracting(ConfigViolation::path)
                .noneMatch(path -> path.endsWith(".promptDelivery"));
    }

    @Test
    void argumentDeliveryThroughAnOrdinaryExecutableIsNotWarnedAbout() throws Exception {
        JTestForgeConfig config = withActiveProvider(fullyValidConfig(), resolvableCommand(), PromptDelivery.ARGUMENT);

        assertThat(validator.validate(config, validContext()).warnings()).extracting(ConfigViolation::path)
                .noneMatch(path -> path.endsWith(".promptDelivery"));
    }

    private static JTestForgeConfig withActiveProvider(JTestForgeConfig c, String command, PromptDelivery delivery) {
        String active = c.aiProvider().active();
        AiProviderConfig aiProvider = new AiProviderConfig(active,
                java.util.Map.of(active, new ProviderConfig(command, java.util.List.of(), delivery, null, null, null, null)),
                c.aiProvider().isolateWorkingDirectory());
        return new JTestForgeConfig(c.project(), c.selection(), aiProvider, c.prompts(), c.spring(), c.context(),
                c.generate(), c.harden(), c.execution());
    }

    // --- HTTP providers (phase B1) ------------------------------------------------------------

    @Test
    void aLocalHttpProviderNeedsNoCommandAndIsFullyValid() throws Exception {
        JTestForgeConfig config = withHttpProvider(fullyValidConfig(), null, "http://localhost:11434", "qwen3-coder:30b", null);

        ValidationResult result = validator.validate(config, validContext());

        assertThat(result.errors()).isEmpty();
        assertThat(result.warnings()).extracting(ConfigViolation::path).noneMatch(path -> path.endsWith(".baseUrl"));
    }

    @Test
    void anHttpProviderMissingItsEndpointOrModelIsReportedByName() throws Exception {
        JTestForgeConfig config = withHttpProvider(fullyValidConfig(), null, null, "", null);

        assertThat(validator.validate(config, validContext()).errors()).extracting(ConfigViolation::path)
                .contains("aiProvider.providers.claude.baseUrl", "aiProvider.providers.claude.model");
    }

    @Test
    void aBaseUrlThatIsNotAnHttpUrlWithAHostIsRejected() throws Exception {
        for (String bad : new String[] {"localhost:11434", "ftp://localhost/x", "not a url", "http://"}) {
            JTestForgeConfig config = withHttpProvider(fullyValidConfig(), null, bad, "m", null);

            assertThat(validator.validate(config, validContext()).errors()).as(bad).extracting(ConfigViolation::path)
                    .contains("aiProvider.providers.claude.baseUrl");
        }
    }

    @Test
    void aCommandOnAnHttpProviderIsRefusedBecauseItWouldNeverRun() throws Exception {
        JTestForgeConfig config = withHttpProvider(fullyValidConfig(), "ollama", "http://localhost:11434", "m", null);

        assertThat(validator.validate(config, validContext()).errors()).extracting(ConfigViolation::path)
                .contains("aiProvider.providers.claude.command");
    }

    @Test
    void anEndpointThatIsNotThisMachineIsWarnedAboutBecauseTheSourceIsSentThere() throws Exception {
        JTestForgeConfig config = withHttpProvider(fullyValidConfig(), null, "https://llm.corp.example:8443", "m", null);

        ValidationResult result = validator.validate(config, validContext());

        assertThat(result.errors()).isEmpty();
        assertThat(result.warnings()).anySatisfy(warning -> assertThat(warning.toString())
                .contains("llm.corp.example").contains("sent there"));
    }

    @Test
    void loopbackAddressesAreNotWarnedAbout() throws Exception {
        for (String local : new String[] {"http://127.0.0.1:11434", "http://localhost", "http://[::1]:11434",
                "http://ollama.localhost:11434"}) {
            JTestForgeConfig config = withHttpProvider(fullyValidConfig(), null, local, "m", null);

            assertThat(validator.validate(config, validContext()).warnings()).as(local)
                    .extracting(ConfigViolation::path).noneMatch(path -> path.endsWith(".baseUrl"));
        }
    }

    @Test
    void anApiOtherThanOllamaIsRejectedForNow() throws Exception {
        JTestForgeConfig config = withHttpProvider(fullyValidConfig(), null, "http://localhost:11434", "m", "openai");

        assertThat(validator.validate(config, validContext()).errors()).extracting(ConfigViolation::path)
                .contains("aiProvider.providers.claude.api");
    }

    @Test
    void aProcessProviderWithNoCommandIsReportedInsteadOfFailingLater() throws Exception {
        JTestForgeConfig base = fullyValidConfig();
        String active = base.aiProvider().active();
        JTestForgeConfig config = new JTestForgeConfig(base.project(), base.selection(),
                new AiProviderConfig(active, java.util.Map.of(active,
                        new ProviderConfig(null, null, null, null, null, null, null)),
                        base.aiProvider().isolateWorkingDirectory()),
                base.prompts(), base.spring(), base.context(), base.generate(), base.harden(), base.execution());

        assertThat(validator.validate(config, validContext()).errors()).extracting(ConfigViolation::path)
                .contains("aiProvider.providers." + active + ".command");
    }

    @Test
    void headersTheHttpClientRefusesAreConfigErrorsNamingTheHeader() throws Exception {
        for (String restricted : new String[] {"Host", "Content-Length", "Connection", "Upgrade", "Expect"}) {
            JTestForgeConfig config = withHttpProvider(fullyValidConfig(), null, "http://localhost:11434", "m", null,
                    java.util.Map.of(restricted, "x"));

            assertThat(validator.validate(config, validContext()).errors()).as(restricted)
                    .extracting(ConfigViolation::path).contains("aiProvider.providers.claude.headers." + restricted);
        }
    }

    @Test
    void ordinaryHeadersAreAccepted() throws Exception {
        JTestForgeConfig config = withHttpProvider(fullyValidConfig(), null, "http://localhost:11434", "m", null,
                java.util.Map.of("Authorization", "Bearer x", "X-Team", "qa", "Content-Type", "application/json"));

        assertThat(validator.validate(config, validContext()).errors()).isEmpty();
    }

    private static JTestForgeConfig withHttpProvider(
            JTestForgeConfig c, String command, String baseUrl, String model, String api) {
        return withHttpProvider(c, command, baseUrl, model, api, null);
    }

    private static JTestForgeConfig withHttpProvider(
            JTestForgeConfig c, String command, String baseUrl, String model, String api,
            java.util.Map<String, String> headers) {
        String active = c.aiProvider().active();
        ProviderConfig http = new ProviderConfig(command, null, null, null, null, null, null,
                ProviderType.HTTP, api, baseUrl, model, null, null, headers);
        return new JTestForgeConfig(c.project(), c.selection(),
                new AiProviderConfig(active, java.util.Map.of(active, http), c.aiProvider().isolateWorkingDirectory()),
                c.prompts(), c.spring(), c.context(), c.generate(), c.harden(), c.execution());
    }
}
