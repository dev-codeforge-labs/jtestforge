package com.devmanchego.jtestforge.cli;

import com.devmanchego.jtestforge.analysis.MockBeanSynthesizer;
import com.devmanchego.jtestforge.analysis.ProductionClassScanner;
import com.devmanchego.jtestforge.analysis.ProductionTypeSolvers;
import com.devmanchego.jtestforge.analysis.TestClassLocator;
import com.devmanchego.jtestforge.analysis.TestClassMerger;
import com.devmanchego.jtestforge.analysis.TestClassReverter;
import com.devmanchego.jtestforge.analysis.TestClassScanner;
import com.devmanchego.jtestforge.build.MavenClasspathResolver;
import com.devmanchego.jtestforge.build.MavenModuleBuild;
import com.devmanchego.jtestforge.build.MavenRunner;
import com.devmanchego.jtestforge.build.ModuleBuild;
import com.devmanchego.jtestforge.build.ModuleDependencyResolver;
import com.devmanchego.jtestforge.build.TestFrameworkDetector;
import com.devmanchego.jtestforge.config.JTestForgeConfig;
import com.devmanchego.jtestforge.config.SpringEnabledMode;
import com.devmanchego.jtestforge.coverage.CoverageDeltaCalculator;
import com.devmanchego.jtestforge.coverage.JacocoReportParser;
import com.devmanchego.jtestforge.guard.StaticQualityGuards;
import com.devmanchego.jtestforge.model.Baseline;
import com.devmanchego.jtestforge.model.ClassCoverage;
import com.devmanchego.jtestforge.model.ModuleDependency;
import com.devmanchego.jtestforge.model.Phase;
import com.devmanchego.jtestforge.model.ProductionClass;
import com.devmanchego.jtestforge.model.RunState;
import com.devmanchego.jtestforge.model.SpringStackFacts;
import com.devmanchego.jtestforge.model.SpringTierState;
import com.devmanchego.jtestforge.model.TestFrameworkVersions;
import com.devmanchego.jtestforge.model.WorkUnit;
import com.devmanchego.jtestforge.orchestration.CoverageAndGapAcceptanceGate;
import com.devmanchego.jtestforge.orchestration.DefaultUnitProcessor;
import com.devmanchego.jtestforge.orchestration.DiscoveredUnit;
import com.devmanchego.jtestforge.orchestration.DiscoveryInputs;
import com.devmanchego.jtestforge.orchestration.GenerateEngine;
import com.devmanchego.jtestforge.orchestration.GenerateResult;
import com.devmanchego.jtestforge.orchestration.SpringGenerationSupport;
import com.devmanchego.jtestforge.orchestration.TierRestriction;
import com.devmanchego.jtestforge.orchestration.UnitContext;
import com.devmanchego.jtestforge.orchestration.UnitPromptFactory;
import com.devmanchego.jtestforge.orchestration.ValueGate;
import com.devmanchego.jtestforge.orchestration.WorkUnitDiscovery;
import com.devmanchego.jtestforge.prompt.ContextAssembler;
import com.devmanchego.jtestforge.prompt.PromptRenderer;
import com.devmanchego.jtestforge.prompt.PromptTemplateLoader;
import com.devmanchego.jtestforge.provider.AiProvider;
import com.devmanchego.jtestforge.provider.ResponseParser;
import com.devmanchego.jtestforge.provider.TranscriptWriter;
import com.devmanchego.jtestforge.spring.ContextKeyGuard;
import com.devmanchego.jtestforge.spring.ContextKeyModel;
import com.devmanchego.jtestforge.spring.ContextKeyStabilityTracker;
import com.devmanchego.jtestforge.spring.MockBeanEscalation;
import com.devmanchego.jtestforge.spring.MockBeanSetResolver;
import com.devmanchego.jtestforge.spring.SpringStackDetector;
import com.devmanchego.jtestforge.spring.TierClassifier;
import com.devmanchego.jtestforge.state.ResumeReconciler;
import com.devmanchego.jtestforge.state.StateStore;
import com.devmanchego.jtestforge.util.ProcessRunner;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Assembles the real object graph behind {@code jtestforge generate} from a bound
 * {@link JTestForgeConfig} and drives one run against the target module.
 *
 * <p>Structurally this is {@code FixtureModuleHarness} (jtestforge-core's own phase-18
 * end-to-end test harness) promoted to production code: the same components, wired the
 * same way, but reading real config instead of hard-coded test values, and resolving the
 * target module's classpath, dependencies, Spring stack and baseline coverage for real
 * instead of trusting a fixture's known shape.
 */
final class GenerateRunner {

    private static final Duration BUILD_TIMEOUT = Duration.ofMinutes(10);
    private static final Duration CLASSPATH_TIMEOUT = Duration.ofMinutes(2);
    private static final Duration DEPENDENCY_TIMEOUT = Duration.ofMinutes(2);

    private final JTestForgeConfig config;
    private final Path modulePath;
    private final Path stateDir;
    private final Path configBaseDir;
    private final String configHash;

    private final MavenRunner mavenRunner;
    private final ModuleBuild moduleBuild;
    private final StateStore stateStore;

    private SpringStackFacts springFacts;
    private SpringTierState springTierState;
    private TestFrameworkVersions frameworkVersions;
    private List<ProductionClass> productionClasses;

    GenerateRunner(JTestForgeConfig config, Path modulePath, Path stateDir, Path configBaseDir, String configHash) {
        this.config = config;
        this.modulePath = modulePath;
        this.stateDir = stateDir;
        this.configBaseDir = configBaseDir;
        this.configHash = configHash;
        this.mavenRunner = buildMavenRunner();
        this.moduleBuild = new MavenModuleBuild(mavenRunner, modulePath, BUILD_TIMEOUT);
        this.stateStore = new StateStore(stateDir, Clock.systemUTC());
    }

    StateStore stateStore() {
        return stateStore;
    }

    /**
     * Resolves the module's dependency list and decides its Spring stack - cheap next to
     * the baseline build, and needed before config validation can accurately judge
     * {@code spring.enabled}. Idempotent: a second call re-detects rather than caching
     * forever, since nothing here is expensive enough to warrant guarding against re-use.
     */
    SpringStackFacts detectSpringFacts() {
        List<ModuleDependency> dependencies = new ModuleDependencyResolver(
                new ProcessRunner(), config.project().mavenExecutable(), config.project().mavenArgs())
                .resolveDependencies(modulePath, DEPENDENCY_TIMEOUT);
        this.springFacts = new SpringStackDetector().detect(dependencies);
        this.frameworkVersions = new TestFrameworkDetector().detect(dependencies);
        this.springTierState = effectivelyEnabled(springFacts)
                ? new SpringStackDetector().resolveTierAvailability(springFacts, config.spring())
                : SpringTierState.springDisabled(config.spring().maxContextLoadsPerRun());
        return springFacts;
    }

    /**
     * {@code spring.enabled} is a switch {@link SpringStackDetector#resolveTierAvailability}
     * itself does not consult (it only reads {@code spring.tiers} and the detected facts):
     * an explicit {@code false} must force every Spring tier unavailable regardless of what
     * is genuinely on the classpath, and {@code auto} without {@code spring-test} present
     * must behave the same way. Mirrors {@code ConfigValidator}'s own (private) decision.
     */
    private boolean effectivelyEnabled(SpringStackFacts facts) {
        SpringEnabledMode mode = config.spring().enabled();
        return mode == SpringEnabledMode.ENABLED
                || (mode == SpringEnabledMode.AUTO && facts.springTestPresent());
    }

    List<DiscoveredUnit> discoverUnits() {
        if (springFacts == null) {
            detectSpringFacts();
        }
        Path mainSourceRoot = modulePath.resolve(config.project().mainSourceRoot());
        List<Path> classpath = new MavenClasspathResolver(
                new ProcessRunner(), config.project().mavenExecutable(), config.project().mavenArgs())
                .resolveCompileClasspath(modulePath, CLASSPATH_TIMEOUT);
        var typeSolver = ProductionTypeSolvers.forModule(mainSourceRoot, classpath);
        this.productionClasses = new ProductionClassScanner(typeSolver, mainSourceRoot).scan();

        Map<String, ClassCoverage> baselineCoverage = measureBaselineCoverage();

        WorkUnitDiscovery discovery = new WorkUnitDiscovery(config, testClassLocator());
        return discovery.discover(new DiscoveryInputs(
                productionClasses, baselineCoverage, springTierState, springFacts, frameworkVersions));
    }

    /** §9 step 2: one whole-module build, parsed once - not per-class, unlike a unit's own value gate. */
    private Map<String, ClassCoverage> measureBaselineCoverage() {
        mavenRunner.run(modulePath, List.of("test", "jacoco:report"), BUILD_TIMEOUT);
        Path report = modulePath.resolve("target/site/jacoco/jacoco.xml");
        if (!Files.isRegularFile(report)) {
            return Map.of();
        }
        Map<String, ClassCoverage> byFqn = new LinkedHashMap<>();
        for (ClassCoverage coverage : new JacocoReportParser().parse(report)) {
            byFqn.put(coverage.fqn(), coverage);
        }
        return Map.copyOf(byFqn);
    }

    GenerateResult run(List<DiscoveredUnit> discovered, AiProvider provider, String providerId,
                       Duration providerTimeout, TierRestriction restriction, int maxUnitsOverride,
                       boolean restart) {
        Map<String, DiscoveredUnit> byUnitId = new LinkedHashMap<>();
        for (DiscoveredUnit unit : discovered) {
            byUnitId.put(unit.workUnit().id().format(), unit);
        }

        RunState initialState = loadOrCreateState(discovered, providerId, restart);

        ContextKeyStabilityTracker tracker =
                new ContextKeyStabilityTracker(config.spring().maxContextLoadsPerRun());
        SpringGenerationSupport springSupport = new SpringGenerationSupport(
                new ContextKeyGuard(), new MockBeanEscalation(), new MockBeanSynthesizer(),
                new MockBeanSetResolver(), new ContextKeyModel(), tracker);

        DefaultUnitProcessor processor = new DefaultUnitProcessor(
                provider,
                new TranscriptWriter(stateDir),
                new UnitPromptFactory(new PromptTemplateLoader().load(config.prompts(), configBaseDir),
                        new PromptRenderer(config.context().maxPromptChars()),
                        new ContextAssembler(config.context())),
                new ResponseParser(),
                new StaticQualityGuards(config.generate(), config.spring()),
                new TestClassMerger(),
                new TestClassReverter(),
                moduleBuild,
                new CoverageAndGapAcceptanceGate(moduleBuild, new CoverageDeltaCalculator(),
                        new ValueGate(config.generate())),
                config.generate(),
                providerTimeout,
                springSupport);

        var executionConfig = maxUnitsOverride > 0
                ? new com.devmanchego.jtestforge.config.ExecutionConfig(
                        stateDir.toString(), config.execution().backupOriginalTests(),
                        config.execution().dryRun(), config.execution().consecutiveFailureAbort(),
                        maxUnitsOverride)
                : config.execution();

        GenerateEngine engine = new GenerateEngine(processor, moduleBuild, stateStore, executionConfig, tracker);
        return engine.run(initialState, workUnit -> contextFor(byUnitId, workUnit), restriction);
    }

    /**
     * §8.2.3: an existing state file is resumed and reconciled against the filesystem by
     * default (a stale {@code DONE} claim is reset to {@code PENDING}); {@code --restart}
     * discards it and starts over with freshly discovered units.
     */
    private RunState loadOrCreateState(List<DiscoveredUnit> discovered, String providerId, boolean restart) {
        if (!restart) {
            var existing = stateStore.load();
            if (existing.isPresent()) {
                ResumeReconciler reconciler = new ResumeReconciler(new TestClassScanner()::testMethodNames);
                var reconciliation = reconciler.reconcile(existing.get(), modulePath, configHash);
                for (var unitId : reconciliation.unitsNeedingRevert()) {
                    reconciliation.state().unit(unitId).ifPresent(unit -> {
                        Path testFile = resolveAgainstModule(unit.testFile());
                        new TestClassReverter().revert(testFile, unit.addedTests(), unit.addedImports());
                    });
                }
                return reconciliation.state();
            }
        }
        return RunState.startNew(UUID.randomUUID().toString(), Instant.now(), Phase.GENERATE,
                        modulePath.toString(), configHash, providerId, springTierState,
                        discovered.stream().map(DiscoveredUnit::workUnit).toList())
                .withBaseline(new Baseline(aggregateLineCoverage(), aggregateBranchCoverage(),
                        null, totalOpenGaps(discovered)));
    }

    private Path resolveAgainstModule(String testFile) {
        Path path = Path.of(testFile);
        return path.isAbsolute() ? path : modulePath.resolve(path);
    }

    private int totalOpenGaps(List<DiscoveredUnit> discovered) {
        return discovered.stream().mapToInt(unit -> unit.gaps().size()).sum();
    }

    private double aggregateLineCoverage() {
        return aggregateCoverage(ClassCoverage::linesCovered, ClassCoverage::linesMissed);
    }

    private double aggregateBranchCoverage() {
        return aggregateCoverage(ClassCoverage::branchesCovered, ClassCoverage::branchesMissed);
    }

    private double aggregateCoverage(java.util.function.ToIntFunction<ClassCoverage> covered,
                                     java.util.function.ToIntFunction<ClassCoverage> missed) {
        // Recomputed from the same baseline report discovery just parsed, rather than
        // threaded through as a separate parameter - one source of truth for "what did
        // the baseline JaCoCo report say", read twice for two different summaries of it.
        Path report = modulePath.resolve("target/site/jacoco/jacoco.xml");
        if (!Files.isRegularFile(report)) {
            return 0.0;
        }
        List<ClassCoverage> classes = new JacocoReportParser().parse(report);
        long totalCovered = classes.stream().mapToLong(covered::applyAsInt).sum();
        long totalMissed = classes.stream().mapToLong(missed::applyAsInt).sum();
        long total = totalCovered + totalMissed;
        return total == 0 ? 0.0 : (double) totalCovered / total;
    }

    /**
     * Builds a unit's context at the moment it runs, re-reading its test class each time -
     * earlier units in the same class may have just written to it.
     */
    private UnitContext contextFor(Map<String, DiscoveredUnit> byUnitId, WorkUnit workUnit) {
        DiscoveredUnit discovered = byUnitId.get(workUnit.id().format());
        if (discovered == null) {
            throw new IllegalStateException("No discovered unit matches " + workUnit.id());
        }
        var testClassInfo = new TestClassScanner().scan(discovered.testFile()).orElse(null);
        return new UnitContext(workUnit, discovered.productionClass(), discovered.targetMethod(),
                discovered.testFile(), discovered.testClassSimpleName(), testClassInfo,
                discovered.gaps(), discovered.coverageBefore(), springFacts, frameworkVersions,
                new MockBeanSetResolver().resolve(discovered.productionClass()),
                new TierClassifier().classify(discovered.productionClass()));
    }

    private TestClassLocator testClassLocator() {
        return new TestClassLocator(new TestClassScanner(),
                modulePath.resolve(config.project().testSourceRoot()),
                config.project().testClassSuffix(), config.project().testClassSuffixByTier());
    }

    private MavenRunner buildMavenRunner() {
        String javaHome = config.project().javaHome();
        return javaHome == null
                ? new MavenRunner(new ProcessRunner(), config.project().mavenExecutable(), config.project().mavenArgs())
                : new MavenRunner(new ProcessRunner(), config.project().mavenExecutable(),
                        config.project().mavenArgs(), Path.of(javaHome));
    }
}
