package com.devmanchego.jtestforge.config;

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
        checkActiveProviderResolvable(config, context, errors);
        boolean springActive = checkSpringEnabled(config, context, errors, warnings);
        checkAlwaysRequiredPrompts(config, context, errors, springActive);
        checkSpringTierPrompts(config, context, errors, springActive);
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

    private void checkActiveProviderResolvable(
            JTestForgeConfig config, ValidationContext context, List<ConfigViolation> errors) {
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
        if (!ExecutableResolver.isResolvable(provider.command(), context.pathDirectories())) {
            errors.add(new ConfigViolation("aiProvider.providers." + active + ".command",
                    "not found on PATH and not an existing path: " + provider.command()));
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
