package com.devmanchego.jtestforge.cli;

import com.devmanchego.jtestforge.analysis.TestClassReverter;
import com.devmanchego.jtestforge.config.ConfigLoadException;
import com.devmanchego.jtestforge.config.ConfigLoadResult;
import com.devmanchego.jtestforge.config.ConfigValidator;
import com.devmanchego.jtestforge.config.JTestForgeConfig;
import com.devmanchego.jtestforge.config.ProviderConfig;
import com.devmanchego.jtestforge.config.ValidationContext;
import com.devmanchego.jtestforge.config.ValidationResult;
import com.devmanchego.jtestforge.model.Tier;
import com.devmanchego.jtestforge.orchestration.DiscoveredUnit;
import com.devmanchego.jtestforge.orchestration.GenerateResult;
import com.devmanchego.jtestforge.orchestration.InterruptHandler;
import com.devmanchego.jtestforge.orchestration.TierRestriction;
import com.devmanchego.jtestforge.provider.AiProvider;
import com.devmanchego.jtestforge.provider.ProcessAiProvider;
import com.devmanchego.jtestforge.state.LockFile;
import com.devmanchego.jtestforge.state.LockHeldException;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;

/**
 * {@code jtestforge generate} — jtestforge-specification.md §9, §14. Pass 1: coverage- and
 * framework-semantic-gap-driven test generation, against a real target module and a real
 * AI provider.
 */
@Command(
        name = "generate",
        description = "Pass 1 - coverage and framework-semantic-gap driven test generation."
)
public final class GenerateCommand implements Callable<Integer> {

    @Mixin
    private CommonModuleOptions options;

    @Option(names = {"-p", "--provider"}, description = "Override aiProvider.active")
    private String providerOverride;

    @Option(names = "--tier", description = "Restrict to one or more tiers: "
            + "PLAIN_UNIT | WEB_SLICE | DATA_SLICE | JSON_SLICE | CONTEXT_SLICE (repeatable)")
    private Set<Tier> tiers;

    @Option(names = "--no-spring", description = "Disable every Spring tier for this invocation")
    private boolean noSpring;

    @Option(names = "--resume", description = "Continue the existing run (default when state exists)")
    private boolean resume;

    @Option(names = "--restart", description = "Discard the existing state and start a fresh run")
    private boolean restart;

    @Option(names = "--max-units", description = "Cap the number of units this invocation processes")
    private int maxUnits;

    @Option(names = "--force-unlock", description = "Clear a stale lock before running")
    private boolean forceUnlock;

    @Spec
    private CommandSpec spec;

    @Override
    public Integer call() {
        ConsoleOutput console = console();

        if (resume && restart) {
            console.error("--resume and --restart are mutually exclusive.");
            return ExitCodes.CONFIGURATION_OR_PREFLIGHT_ERROR;
        }
        if (tiers != null && !tiers.isEmpty() && noSpring) {
            console.error("--tier and --no-spring are mutually exclusive.");
            return ExitCodes.CONFIGURATION_OR_PREFLIGHT_ERROR;
        }

        ConfigLoadResult loaded;
        try {
            loaded = ConfigResolver.load(options);
        } catch (ConfigLoadException e) {
            console.error(e.getMessage());
            return ExitCodes.CONFIGURATION_OR_PREFLIGHT_ERROR;
        }
        JTestForgeConfig config = loaded.config();

        Path modulePath = ConfigResolver.modulePathOf(config);
        Integer preflightError = checkModulePreflight(console, config, modulePath);
        if (preflightError != null) {
            return preflightError;
        }

        String providerId = providerOverride != null ? providerOverride : config.aiProvider().active();
        ProviderConfig providerConfig = config.aiProvider().providers().get(providerId);
        if (providerConfig == null) {
            console.error("aiProvider has no provider named \"" + providerId + "\" under aiProvider.providers.");
            return ExitCodes.CONFIGURATION_OR_PREFLIGHT_ERROR;
        }

        Path stateDir = ConfigResolver.stateDirOf(config, modulePath);
        if (forceUnlock) {
            LockFile.forceUnlock(stateDir);
        }

        LockFile lock;
        try {
            lock = LockFile.acquire(stateDir, Clock.systemUTC());
        } catch (LockHeldException e) {
            console.error(e.getMessage());
            return ExitCodes.LOCK_HELD;
        }

        try {
            return runLocked(console, config, modulePath, stateDir, loaded.configHash(),
                    providerId, providerConfig, lock);
        } finally {
            lock.release();
        }
    }

    private Integer runLocked(ConsoleOutput console, JTestForgeConfig config, Path modulePath, Path stateDir,
                              String configHash, String providerId, ProviderConfig providerConfig, LockFile lock) {
        Path configBaseDir = ConfigResolver.resolvePath(options).toAbsolutePath().getParent();
        java.util.function.Consumer<String> verboseSink = options.verbose ? console::info : null;
        java.util.function.Consumer<String> progress = console::info;
        GenerateRunner runner = new GenerateRunner(
                config, modulePath, stateDir, configBaseDir, configHash, verboseSink, progress);

        com.devmanchego.jtestforge.model.SpringStackFacts springFacts;
        try {
            springFacts = runner.detectSpringFacts();
        } catch (com.devmanchego.jtestforge.build.MavenClasspathResolutionException
                 | com.devmanchego.jtestforge.build.DependencyTreeException e) {
            runner.moduleResolution().report(console);
            console.error(e.getMessage());
            return ExitCodes.CONFIGURATION_OR_PREFLIGHT_ERROR;
        }
        runner.moduleResolution().report(console);
        Integer frameworkError = checkTestFrameworkPreflight(console, runner);
        if (frameworkError != null) {
            return frameworkError;
        }
        ValidationContext validationContext = new ValidationContext(
                configBaseDir, com.devmanchego.jtestforge.util.ExecutableResolver.systemPathDirectories(),
                springFacts.springTestPresent(), false);
        ValidationResult validation = new ConfigValidator().validate(config, validationContext);
        for (var warning : validation.warnings()) {
            console.warn(warning.toString());
        }
        if (!validation.isValid()) {
            for (var error : validation.errors()) {
                console.error(error.toString());
            }
            return ExitCodes.CONFIGURATION_OR_PREFLIGHT_ERROR;
        }

        Thread hook = new InterruptHandler(runner.stateStore(), lock, new TestClassReverter(), modulePath).install();
        try {
            List<DiscoveredUnit> discovered;
            try {
                discovered = runner.discoverUnits();
            } catch (com.devmanchego.jtestforge.build.MavenClasspathResolutionException
                     | com.devmanchego.jtestforge.build.DependencyTreeException e) {
                runner.moduleResolution().report(console);
                console.error(e.getMessage());
                return ExitCodes.CONFIGURATION_OR_PREFLIGHT_ERROR;
            }
            runner.moduleResolution().report(console);
            AiProvider provider = new ProcessAiProvider(providerId,
                    new com.devmanchego.jtestforge.util.ProcessRunner(
                            java.nio.charset.Charset.defaultCharset(), verboseSink, progress),
                    providerConfig.command(), providerConfig.args(), providerConfig.promptDelivery(),
                    providerConfig.transportRetries(),
                    providerWorkingDirectory(console, config, modulePath, stateDir),
                    providerConfig.env());
            Duration providerTimeout = Duration.ofSeconds(providerConfig.timeoutSeconds());
            TierRestriction restriction = tierRestriction();

            GenerateResult result = runner.run(discovered, provider, providerId, providerTimeout,
                    restriction, maxUnits, restart);

            printSummary(console, result);
            return ExitCodeMapper.forGenerate(result);
        } finally {
            new InterruptHandler(runner.stateStore(), lock, new TestClassReverter(), modulePath).uninstall(hook);
        }
    }

    /**
     * Falls back to the module path rather than failing the run: a scratch directory that
     * cannot be created is a reason to be slower, never a reason to generate nothing.
     */
    private Path providerWorkingDirectory(ConsoleOutput console, JTestForgeConfig config,
                                          Path modulePath, Path stateDir) {
        boolean isolate = config.aiProvider().isolateWorkingDirectory();
        try {
            Path resolved = com.devmanchego.jtestforge.provider.ProviderWorkingDirectory.resolve(
                    isolate, modulePath, stateDir);
            if (isolate) {
                console.info("AI CLI runs in an empty directory (" + resolved
                        + ") so an agentic provider has no workspace to explore"
                        + " - set aiProvider.isolateWorkingDirectory: false to use the module instead.");
            }
            return resolved;
        } catch (java.io.IOException e) {
            console.warn("Could not create the isolated AI working directory under " + stateDir
                    + " (" + e.getMessage() + "); falling back to the module directory.");
            return modulePath;
        }
    }

    private TierRestriction tierRestriction() {
        if (noSpring) {
            return TierRestriction.noSpring();
        }
        if (tiers != null && !tiers.isEmpty()) {
            return TierRestriction.only(tiers);
        }
        return TierRestriction.allTiers();
    }

    private void printSummary(ConsoleOutput console, GenerateResult result) {
        console.info("Run " + result.state().runId() + ": " + result.exitReason());
        var counts = result.state().statusCounts();
        counts.forEach((status, count) -> console.info("  " + status + ": " + count));
        if (!result.fullSuiteFailures().isEmpty()) {
            console.error(result.exitReason() == GenerateResult.ExitReason.PREFLIGHT_FAILED
                    ? "The module was not usable before the run started - nothing was generated:"
                    : "The module's full suite is failing at the end of the run:");
            result.fullSuiteFailures().forEach(failure -> console.error("  " + failure));
        }
    }

    /**
     * JTestForge generates JUnit 5 and only JUnit 5: the skeleton it creates for a new test
     * class imports {@code org.junit.jupiter.api.Test}, and the response contract requires
     * {@code @Test} methods. On a module without Jupiter on its test classpath, every
     * single generated class therefore fails to compile for a reason the model cannot fix,
     * however many repair rounds it is given - so this is refused up front rather than
     * discovered one expensive AI call at a time.
     */
    private Integer checkTestFrameworkPreflight(ConsoleOutput console, GenerateRunner runner) {
        var frameworks = runner.frameworkVersions();
        if (frameworks == null || frameworks.junitJupiter() != null) {
            return null;
        }
        console.error("This module has no JUnit Jupiter (JUnit 5) on its test classpath, and JTestForge"
                + " generates JUnit 5 tests - every generated class would fail to compile.");
        console.error("  Add junit-jupiter to the module's test scope, then run again.");
        return ExitCodes.CONFIGURATION_OR_PREFLIGHT_ERROR;
    }

    private Integer checkModulePreflight(ConsoleOutput console, JTestForgeConfig config, Path modulePath) {
        if (!Files.isRegularFile(modulePath.resolve("pom.xml"))) {
            console.error("Not a Maven module (no pom.xml found): " + modulePath);
            return ExitCodes.CONFIGURATION_OR_PREFLIGHT_ERROR;
        }
        Path mainSourceRoot = modulePath.resolve(config.project().mainSourceRoot());
        if (!Files.isDirectory(mainSourceRoot)) {
            console.error("No main source root at " + mainSourceRoot);
            return ExitCodes.CONFIGURATION_OR_PREFLIGHT_ERROR;
        }
        return null;
    }

    private ConsoleOutput console() {
        return new ConsoleOutput(spec.commandLine().getOut(), spec.commandLine().getErr());
    }
}
