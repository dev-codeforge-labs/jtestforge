package com.devmanchego.jtestforge.config;

import com.devmanchego.jtestforge.build.JavaRelease;
import com.devmanchego.jtestforge.model.Tier;
import com.devmanchego.jtestforge.util.ExecutableResolver;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Checks a bound {@link JTestForgeConfig} against every rule in
 * jtestforge-specification.md §5.1, collecting every violation before returning rather
 * than failing on the first one found.
 */
public final class ConfigValidator {

    public ValidationResult validate(JTestForgeConfig config, ValidationContext context) {
        List<ConfigViolation> errors = new ArrayList<>();
        List<ConfigViolation> warnings = new ArrayList<>();

        checkModulePath(config, errors);
        checkDependencyInputs(config, context, errors);
        checkActiveProviderResolvable(config, context, errors, warnings);
        boolean springActive = checkSpringEnabled(config, context, errors, warnings);
        checkAlwaysRequiredPrompts(config, context, errors, springActive);
        checkSpringTierPrompts(config, context, errors, springActive);
        checkFixContractPrompt(config, context, warnings);
        checkArgumentDeliveryThroughBatchLauncher(config, context, warnings);
        checkStandaloneWrapperJar(config, context, errors);
        checkStateDirNotUnderTarget(config, errors);
        checkPositiveValues(config, errors);
        checkMaxTierForMutation(config, errors);
        checkTestcontainers(config, context, errors);

        return new ValidationResult(errors, warnings);
    }

    private void checkModulePath(JTestForgeConfig config, List<ConfigViolation> errors) {
        String rawModulePath = config.project().modulePath();
        if (rawModulePath == null || rawModulePath.isBlank()) {
            errors.add(new ConfigViolation("project.modulePath", "is required."));
            return;
        }
        Path modulePath = Path.of(rawModulePath);
        if (!Files.isDirectory(modulePath)) {
            errors.add(new ConfigViolation("project.modulePath",
                    "does not exist or is not a directory: " + rawModulePath));
            return;
        }
        if (!Files.isRegularFile(modulePath.resolve("pom.xml"))) {
            errors.add(new ConfigViolation("project.modulePath",
                    "does not contain a pom.xml: " + rawModulePath));
        }
    }

    private void checkDependencyInputs(
            JTestForgeConfig config, ValidationContext context, List<ConfigViolation> errors) {
        ProjectConfig project = config.project();
        String treeFile = project.dependencyTreeFile();
        if (treeFile != null && !treeFile.isBlank() && !Files.isReadable(resolveAgainstBase(context, treeFile))) {
            errors.add(new ConfigViolation("project.dependencyTreeFile", "file not found or not readable: " + treeFile));
        }
        String localRepository = project.localRepository();
        if (localRepository != null && !localRepository.isBlank()
                && !Files.isDirectory(resolveAgainstBase(context, localRepository))) {
            errors.add(new ConfigViolation("project.localRepository",
                    "does not exist or is not a directory: " + localRepository));
        }
        String javaVersion = project.javaVersion();
        if (javaVersion != null && !javaVersion.isBlank() && JavaRelease.parse(javaVersion) == 0) {
            errors.add(new ConfigViolation("project.javaVersion",
                    "is not a Java version: \"" + javaVersion + "\" (expected e.g. 8, 1.8, 11, 17)."));
        }
        String sourceEncoding = project.sourceEncoding();
        if (sourceEncoding != null && !sourceEncoding.isBlank() && !isSupportedCharset(sourceEncoding.strip())) {
            errors.add(new ConfigViolation("project.sourceEncoding",
                    "is not a character encoding this JVM supports: \"" + sourceEncoding
                            + "\" (expected e.g. UTF-8, ISO-8859-1, windows-1252)."));
        }
    }

    private static boolean isSupportedCharset(String name) {
        try {
            return java.nio.charset.Charset.isSupported(name);
        } catch (IllegalArgumentException illegalName) {
            return false;
        }
    }

    private void checkActiveProviderResolvable(
            JTestForgeConfig config, ValidationContext context,
            List<ConfigViolation> errors, List<ConfigViolation> warnings) {
        String active = config.aiProvider().active();
        if (active == null || active.isBlank()) {
            errors.add(new ConfigViolation("aiProvider.active", "is required."));
            return;
        }
        ProviderConfig provider = config.aiProvider().providers().get(active);
        if (provider == null) {
            errors.add(new ConfigViolation("aiProvider.active",
                    "names provider \"" + active + "\", which is not defined under aiProvider.providers."));
            return;
        }
        String path = "aiProvider.providers." + active;
        if (provider.isHttp()) {
            checkHttpProvider(path, provider, errors, warnings);
            return;
        }
        if (provider.command() == null || provider.command().isBlank()) {
            errors.add(new ConfigViolation(path + ".command", "is required for a provider of type process."));
        } else if (!ExecutableResolver.isResolvable(provider.command(), context.pathDirectories())) {
            errors.add(new ConfigViolation(path + ".command",
                    "not found on PATH and not an existing path: " + provider.command()));
        }
    }

    /**
     * An HTTP provider is judged on what it needs, not on a command: the endpoint and the model
     * are required, a stray {@code command} is refused (it would never run, and leaving it would
     * suggest it does), and an endpoint that is not this machine is warned about because the
     * prompt - the developer's source code - is sent to it.
     */
    private void checkHttpProvider(String path, ProviderConfig provider,
                                   List<ConfigViolation> errors, List<ConfigViolation> warnings) {
        if (provider.command() != null && !provider.command().isBlank()) {
            errors.add(new ConfigViolation(path + ".command", "is not allowed for a provider of type http "
                    + "(it is launched as a process only for type process)."));
        }
        if (!"ollama".equals(provider.api())) {
            errors.add(new ConfigViolation(path + ".api", "is not supported: \"" + provider.api()
                    + "\" (only \"ollama\" for now)."));
        }
        if (provider.model() == null || provider.model().isBlank()) {
            errors.add(new ConfigViolation(path + ".model", "is required for a provider of type http."));
        }
        checkBaseUrl(path, provider.baseUrl(), errors, warnings);
        if (!provider.args().isEmpty()) {
            warnings.add(new ConfigViolation(path + ".args", "is ignored for a provider of type http."));
        }
    }

    private void checkBaseUrl(String path, String baseUrl,
                              List<ConfigViolation> errors, List<ConfigViolation> warnings) {
        if (baseUrl == null || baseUrl.isBlank()) {
            errors.add(new ConfigViolation(path + ".baseUrl", "is required for a provider of type http."));
            return;
        }
        java.net.URI uri;
        try {
            uri = java.net.URI.create(baseUrl.strip());
        } catch (IllegalArgumentException e) {
            uri = null;
        }
        boolean usable = uri != null && uri.getHost() != null
                && ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()));
        if (!usable) {
            errors.add(new ConfigViolation(path + ".baseUrl",
                    "is not an http(s) URL with a host: \"" + baseUrl + "\" (expected e.g. http://localhost:11434)."));
            return;
        }
        String host = uri.getHost().toLowerCase(java.util.Locale.ROOT);
        boolean local = host.equals("localhost") || host.endsWith(".localhost") || host.equals("127.0.0.1")
                || host.equals("[::1]") || host.equals("::1");
        if (!local) {
            warnings.add(new ConfigViolation(path + ".baseUrl", "points at " + uri.getHost()
                    + ", not this machine: every prompt - including the full source of the classes under "
                    + "test - is sent there."));
        }
    }

    /** @return whether Spring support is effectively active for this run. */
    private boolean checkSpringEnabled(
            JTestForgeConfig config, ValidationContext context,
            List<ConfigViolation> errors, List<ConfigViolation> warnings) {
        SpringEnabledMode mode = config.spring().enabled();
        boolean springTestPresent = context.springTestOnClasspath();

        if (mode == SpringEnabledMode.ENABLED && !springTestPresent) {
            errors.add(new ConfigViolation("spring.enabled",
                    "is true, but no spring-test / spring-boot-starter-test was detected "
                            + "on the module's test classpath."));
            return false;
        }
        if (mode == SpringEnabledMode.AUTO && !springTestPresent) {
            warnings.add(new ConfigViolation("spring.enabled",
                    "is \"auto\" and no spring-test was detected; all Spring tiers are "
                            + "disabled for this run."));
            return false;
        }
        return mode == SpringEnabledMode.ENABLED || (mode == SpringEnabledMode.AUTO && springTestPresent);
    }

    private void checkAlwaysRequiredPrompts(
            JTestForgeConfig config, ValidationContext context,
            List<ConfigViolation> errors, boolean springActive) {
        PromptsConfig prompts = config.prompts();
        requirePromptFile(context, errors, "prompts.newTestClass", prompts.newTestClass());
        requirePromptFile(context, errors, "prompts.additionalTests", prompts.additionalTests());
        requirePromptFile(context, errors, "prompts.killMutants", prompts.killMutants());
        requirePromptFile(context, errors, "prompts.fixCompilation", prompts.fixCompilation());
        requirePromptFile(context, errors, "prompts.fixAssertion", prompts.fixAssertion());
        requirePromptFile(context, errors, "prompts.rules", prompts.rules());
        if (springActive) {
            requirePromptFile(context, errors, "prompts.springRules", prompts.springRules());
        }
    }

    private void checkSpringTierPrompts(
            JTestForgeConfig config, ValidationContext context,
            List<ConfigViolation> errors, boolean springActive) {
        if (!springActive) {
            return;
        }
        TiersConfig tiers = config.spring().tiers();
        PromptsConfig prompts = config.prompts();
        if (tiers.isEnabled(Tier.WEB_SLICE)) {
            requirePromptFile(context, errors, "prompts.webSliceTests", prompts.webSliceTests());
        }
        if (tiers.isEnabled(Tier.DATA_SLICE)) {
            requirePromptFile(context, errors, "prompts.dataSliceTests", prompts.dataSliceTests());
        }
        if (tiers.isEnabled(Tier.JSON_SLICE)) {
            requirePromptFile(context, errors, "prompts.jsonSliceTests", prompts.jsonSliceTests());
        }
        if (tiers.isEnabled(Tier.CONTEXT_SLICE)) {
            requirePromptFile(context, errors, "prompts.contextTests", prompts.contextTests());
        }
    }

    private void requirePromptFile(
            ValidationContext context, List<ConfigViolation> errors, String path, String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            errors.add(new ConfigViolation(path, "is not set."));
            return;
        }
        if (!Files.isReadable(resolveAgainstBase(context, rawPath))) {
            errors.add(new ConfigViolation(path, "file not found or not readable: " + rawPath));
        }
    }

    /**
     * A warning, not an error: {@code prompts.fixContract} was added after {@code jtestforge init}
     * may already have scaffolded a project, and a missing file falls back to the bundled default
     * (see {@code PromptTemplateId#fallsBackToBundledWhenMissing}). Worth saying, since an edit the
     * user believes they made would otherwise silently not apply.
     */
    private void checkFixContractPrompt(
            JTestForgeConfig config, ValidationContext context, List<ConfigViolation> warnings) {
        String rawPath = config.prompts().fixContract();
        if (rawPath != null && !rawPath.isBlank() && !Files.isReadable(resolveAgainstBase(context, rawPath))) {
            warnings.add(new ConfigViolation("prompts.fixContract", "file not found: " + rawPath
                    + " - the bundled default is used; run 'jtestforge init' to scaffold it."));
        }
    }

    /**
     * {@code promptDelivery: argument} puts the whole prompt - the developer's source code - on the
     * command line. When the AI CLI is a {@code .cmd}/{@code .bat} launcher (Windows runs those
     * through {@code cmd.exe}), characters such as {@code & | < > ^ %} and quotes in that code are
     * interpreted by the shell: the prompt gets mangled, or worse. {@code stdin} (the default) and
     * {@code file} do not have the problem. A warning, not an error: a launcher that is really an
     * executable with a misleading name, or a platform where it is harmless, should still run.
     */
    private void checkArgumentDeliveryThroughBatchLauncher(
            JTestForgeConfig config, ValidationContext context, List<ConfigViolation> warnings) {
        String active = config.aiProvider().active();
        ProviderConfig provider = active == null ? null : config.aiProvider().providers().get(active);
        if (provider == null || provider.isHttp() || provider.promptDelivery() != PromptDelivery.ARGUMENT) {
            return;
        }
        String launcher = ExecutableResolver.resolve(provider.command(), context.pathDirectories())
                .map(java.nio.file.Path::toString).orElse(provider.command());
        String lower = launcher.toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith(".cmd") || lower.endsWith(".bat")) {
            warnings.add(new ConfigViolation("aiProvider.providers." + active + ".promptDelivery",
                    "is \"argument\" but the command (" + launcher + ") is a batch launcher: cmd.exe will interpret "
                            + "& | < > ^ % and quotes in the prompt, and it is subject to its ~8191-character limit. "
                            + "Use \"stdin\" (or \"file\") instead."));
        }
    }

    private void checkStandaloneWrapperJar(
            JTestForgeConfig config, ValidationContext context, List<ConfigViolation> errors) {
        if (config.harden().engine() != HardenEngine.STANDALONE_WRAPPER) {
            return;
        }
        String rawJar = config.harden().standaloneWrapperJar();
        if (rawJar == null || rawJar.isBlank()) {
            errors.add(new ConfigViolation("harden.standaloneWrapperJar",
                    "is required when harden.engine is \"standaloneWrapper\"."));
            return;
        }
        if (!Files.isReadable(resolveAgainstBase(context, rawJar))) {
            errors.add(new ConfigViolation("harden.standaloneWrapperJar",
                    "file not found or not readable: " + rawJar));
        }
    }

    private void checkStateDirNotUnderTarget(JTestForgeConfig config, List<ConfigViolation> errors) {
        Path stateDir = Path.of(config.execution().stateDir());
        for (Path segment : stateDir) {
            if (segment.toString().equals("target")) {
                errors.add(new ConfigViolation("execution.stateDir",
                        "must not be inside a target/ directory - `mvn clean` would wipe the "
                                + "run state: " + config.execution().stateDir()));
                return;
            }
        }
    }

    private void checkPositiveValues(JTestForgeConfig config, List<ConfigViolation> errors) {
        requirePositive(errors, "context.maxPromptChars", config.context().maxPromptChars());
        requirePositive(errors, "spring.contextLoadTimeoutSeconds", config.spring().contextLoadTimeoutSeconds());
        requirePositive(errors, "generate.maxRepairAttempts", config.generate().maxRepairAttempts());
        requirePositive(errors, "generate.maxTestsPerMethod", config.generate().maxTestsPerMethod());
        requirePositive(errors, "harden.maxMutantsPerPrompt", config.harden().maxMutantsPerPrompt());
        requirePositive(errors, "harden.maxAttemptsPerMutant", config.harden().maxAttemptsPerMutant());
        requirePositive(errors, "execution.consecutiveFailureAbort", config.execution().consecutiveFailureAbort());

        for (Map.Entry<String, ProviderConfig> entry : config.aiProvider().providers().entrySet()) {
            requirePositive(errors,
                    "aiProvider.providers." + entry.getKey() + ".timeoutSeconds",
                    entry.getValue().timeoutSeconds());
            Integer maxPromptChars = entry.getValue().maxPromptChars();
            if (maxPromptChars != null) {
                requirePositive(errors,
                        "aiProvider.providers." + entry.getKey() + ".maxPromptChars", maxPromptChars);
            }
        }
    }

    private void requirePositive(List<ConfigViolation> errors, String path, int value) {
        if (value <= 0) {
            errors.add(new ConfigViolation(path, "must be positive, was " + value + "."));
        }
    }

    private void checkMaxTierForMutation(JTestForgeConfig config, List<ConfigViolation> errors) {
        Tier tier = config.harden().maxTierForMutation();
        if (tier == Tier.CONTEXT_SLICE) {
            errors.add(new ConfigViolation("harden.maxTierForMutation",
                    "must not be CONTEXT_SLICE - a full context load per mutant is never "
                            + "permitted (see spec §10.3)."));
            return;
        }
        if (tier != Tier.PLAIN_UNIT && !config.spring().tiers().isEnabled(tier)) {
            errors.add(new ConfigViolation("harden.maxTierForMutation",
                    "names tier " + tier + ", which is disabled in spring.tiers."));
        }
    }

    private void checkTestcontainers(
            JTestForgeConfig config, ValidationContext context, List<ConfigViolation> errors) {
        if (config.spring().allowTestcontainers() && !context.dockerReachable()) {
            errors.add(new ConfigViolation("spring.allowTestcontainers",
                    "is true, but no reachable Docker daemon was detected at preflight."));
        }
    }

    private Path resolveAgainstBase(ValidationContext context, String rawPath) {
        Path candidate = Path.of(rawPath);
        if (candidate.isAbsolute() || context.configBaseDir() == null) {
            return candidate;
        }
        return context.configBaseDir().resolve(candidate);
    }
}
