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
        GenerateRunner runner = new GenerateRunner(config, modulePath, stateDir, configBaseDir, configHash);

        var springFacts = runner.detectSpringFacts();
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
            List<DiscoveredUnit> discovered = runner.discoverUnits();
            AiProvider provider = new ProcessAiProvider(providerId, new com.devmanchego.jtestforge.util.ProcessRunner(),
                    providerConfig.command(), providerConfig.args(), providerConfig.promptDelivery(),
                    providerConfig.transportRetries(), modulePath);
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
            console.error("Full suite failures at the end of the run: " + result.fullSuiteFailures());
        }
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
