package com.devmanchego.jtestforge.cli;

import com.devmanchego.jtestforge.analysis.MockBeanSynthesizer;
import com.devmanchego.jtestforge.analysis.ProductionClassScanner;
import com.devmanchego.jtestforge.analysis.ProductionTypeSolvers;
import com.devmanchego.jtestforge.analysis.TestClassLocator;
import com.devmanchego.jtestforge.analysis.TestClassMerger;
import com.devmanchego.jtestforge.analysis.TestClassReverter;
import com.devmanchego.jtestforge.analysis.TestClassScanner;
import com.devmanchego.jtestforge.build.MavenModuleBuild;
import com.devmanchego.jtestforge.build.MavenRunner;
import com.devmanchego.jtestforge.build.ModuleBuild;
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
import com.devmanchego.jtestforge.orchestration.TestFileBackup;
import com.devmanchego.jtestforge.orchestration.TierRestriction;
import com.devmanchego.jtestforge.orchestration.TierScheduler;
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

    private final JTestForgeConfig config;
    private final Path modulePath;
    private final Path stateDir;
    private final Path configBaseDir;
    private final String configHash;

    private final MavenRunner mavenRunner;
    private final ModuleBuild moduleBuild;
    private final StateStore stateStore;
    private final ModuleResolution resolution;
    private final java.util.function.Consumer<String> verboseSink;
    private final java.util.function.Consumer<String> progress;

    private SpringStackFacts springFacts;
    private SpringTierState springTierState;
    private TestFrameworkVersions frameworkVersions;
    private List<ProductionClass> productionClasses;

    GenerateRunner(JTestForgeConfig config, Path modulePath, Path stateDir, Path configBaseDir, String configHash) {
        this(config, modulePath, stateDir, configBaseDir, configHash, null, null);
    }

    /** @param verboseSink receives every external command (Maven, the AI provider) this run launches - see {@code -v} */
    GenerateRunner(JTestForgeConfig config, Path modulePath, Path stateDir, Path configBaseDir, String configHash,
                  java.util.function.Consumer<String> verboseSink) {
        this(config, modulePath, stateDir, configBaseDir, configHash, verboseSink, null);
    }

    /**
     * @param verboseSink receives every external command (Maven, the AI provider) this run launches - see {@code -v}
     * @param progress    receives one short, content-free line per AI request/response, per
     *                    compile/test-run/coverage check, and per unit start/end - on by
     *                    default, regardless of {@code -v}, so a long run never looks stalled
     */
    GenerateRunner(JTestForgeConfig config, Path modulePath, Path stateDir, Path configBaseDir, String configHash,
                  java.util.function.Consumer<String> verboseSink, java.util.function.Consumer<String> progress) {
        this.config = config;
        this.modulePath = modulePath;
        this.stateDir = stateDir;
        this.configBaseDir = configBaseDir;
        this.configHash = configHash;
        this.verboseSink = verboseSink;
        this.progress = progress;
        this.mavenRunner = buildMavenRunner();
        this.moduleBuild = new MavenModuleBuild(mavenRunner, modulePath, BUILD_TIMEOUT);
        this.stateStore = new StateStore(stateDir, Clock.systemUTC());
        this.resolution = new ModuleResolution(config.project(), modulePath, configBaseDir, verboseSink);
    }

    StateStore stateStore() {
        return stateStore;
    }

    /** Null until {@link #detectSpringFacts()} has run. */
    TestFrameworkVersions frameworkVersions() {
        return frameworkVersions;
    }

    ModuleResolution moduleResolution() {
        return resolution;
    }

    /** The module's source encoding - every test and production file is read and written in it. */
    java.nio.charset.Charset sourceCharset() {
        return resolution.sourceEncoding().charset();
    }

    /** A reverter that writes test files back in {@link #sourceCharset()}. */
    TestClassReverter testClassReverter() {
        return new TestClassReverter(sourceCharset());
    }

    /**
     * Resolves the module's dependency list and decides its Spring stack - cheap next to
     * the baseline build, and needed before config validation can accurately judge
     * {@code spring.enabled}. Idempotent: a second call re-detects rather than caching
     * forever, since nothing here is expensive enough to warrant guarding against re-use.
     */
    SpringStackFacts detectSpringFacts() {
        List<ModuleDependency> dependencies = resolution.dependencies();
        this.springFacts = new SpringStackDetector().detect(dependencies);
        this.frameworkVersions = new TestFrameworkDetector().detect(dependencies)
                .withJavaRelease(resolution.javaVersion().release());
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
        List<Path> classpath = resolution.compileClasspath();
        var typeSolver = ProductionTypeSolvers.forModule(mainSourceRoot, classpath, sourceCharset());
        this.productionClasses = new ProductionClassScanner(
                typeSolver, mainSourceRoot, resolution.javaVersion().release(), sourceCharset()).scan();

        Map<String, ClassCoverage> baselineCoverage = measureBaselineCoverage();

        WorkUnitDiscovery discovery = new WorkUnitDiscovery(config, testClassLocator());
        return discovery.discover(new DiscoveryInputs(
                productionClasses, baselineCoverage, springTierState, springFacts, frameworkVersions));
    }

    /**
     * §9 step 2: one whole-module build, parsed once - not per-class, unlike a unit's own
     * value gate. {@code jacoco:report} is skipped entirely when
     * {@code generate.requireCoverageGain} is off: nothing downstream (the value gate, or
     * unit selection here) ends up needing the numbers, and skipping the call sidesteps a
     * broken JaCoCo setup on the target module rather than making the whole run depend on
     * it needlessly. {@code test} alone is still required - it is the preflight that
     * confirms the module builds green (§2), not just a means to produce coverage data.
     */
    private Map<String, ClassCoverage> measureBaselineCoverage() {
        List<String> goals = config.generate().requireCoverageGain()
                ? List.of("test", "jacoco:report") : List.of("test");
        mavenRunner.run(modulePath, goals, BUILD_TIMEOUT);
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

    /**
     * {@code --dry-run} / {@code execution.dryRun}: everything {@code generate} does up to the AI
     * call, and nothing after it. Discovery has already run (it builds the module and measures
     * coverage, which only touches {@code target/}); this renders the exact prompt each unit
     * would send, in the order they would run and within the same limits, and writes it under
     * {@code <stateDir>/dry-run/} for the user to read. No AI provider is created or called, no
     * test file is written, backed up or created, and {@code state.json} is not touched - so a
     * later real run starts exactly as if this had never happened.
     */
    DryRunReport dryRun(List<DiscoveredUnit> discovered, String providerId,
                        TierRestriction restriction, int maxUnitsOverride) {
        Map<String, DiscoveredUnit> byUnitId = new LinkedHashMap<>();
        for (DiscoveredUnit unit : discovered) {
            byUnitId.put(unit.workUnit().id().format(), unit);
        }
        int limit = maxUnitsOverride > 0 ? maxUnitsOverride : config.execution().maxUnitsPerRun();
        UnitPromptFactory factory = promptFactory(providerId);
        Path promptDir = stateDir.resolve("dry-run");

        List<WorkUnit> pending = discovered.stream().map(DiscoveredUnit::workUnit)
                .filter(unit -> unit.status() == com.devmanchego.jtestforge.model.UnitStatus.PENDING)
                .filter(unit -> restriction.allows(unit.tier()))
                .toList();
        List<String> wouldAttempt = new java.util.ArrayList<>();
        int skipped = discovered.size() - pending.size();
        for (WorkUnit unit : TierScheduler.order(pending)) {
            if (!springTierState.isAvailable(unit.tier())) {
                skipped++;
                continue;
            }
            if (limit > 0 && wouldAttempt.size() >= limit) {
                break;
            }
            UnitContext context = contextFor(byUnitId, unit);
            String prompt = factory.generationPrompt(context);
            Path promptFile = promptDir.resolve(unit.id().format().replaceAll("[:\\\\/*?\"<>|]", "_") + ".prompt.md");
            try {
                com.devmanchego.jtestforge.util.AtomicFileWriter.write(promptFile, prompt);
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException("Failed to write the dry-run prompt " + promptFile, e);
            }
            wouldAttempt.add(unit.id().format() + "  [" + (context.testClassExists() ? "adds to " : "creates ")
                    + context.testFile().getFileName() + ", prompt " + prompt.length() + " chars]");
        }
        return new DryRunReport(wouldAttempt, skipped, promptDir);
    }

    /** What a dry run found: the units a real run would attempt, in order, and where their prompts are. */
    record DryRunReport(List<String> wouldAttempt, int notAttempted, Path promptDirectory) {
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
                new ContextKeyGuard(), new MockBeanEscalation(), new MockBeanSynthesizer(sourceCharset()),
                new MockBeanSetResolver(), new ContextKeyModel(), tracker);

        DefaultUnitProcessor processor = new DefaultUnitProcessor(
                provider,
                new TranscriptWriter(stateDir, progress, verboseSink),
                promptFactory(providerId),
                new ResponseParser(),
                new StaticQualityGuards(config.generate(), config.spring(), sourceCharset()),
                new TestClassMerger(sourceCharset()),
                testClassReverter(),
                moduleBuild,
                new CoverageAndGapAcceptanceGate(moduleBuild, new CoverageDeltaCalculator(),
                        new ValueGate(config.generate())),
                config.generate(),
                providerTimeout,
                springSupport,
                progress,
                sourceCharset());

        var executionConfig = maxUnitsOverride > 0
                ? new com.devmanchego.jtestforge.config.ExecutionConfig(
                        stateDir.toString(), config.execution().backupOriginalTests(),
                        config.execution().dryRun(), config.execution().consecutiveFailureAbort(),
                        maxUnitsOverride)
                : config.execution();

        TestFileBackup backup = config.execution().backupOriginalTests()
                ? new TestFileBackup(stateDir.resolve("backups").resolve(initialState.runId()), modulePath)
                : null;
        GenerateEngine engine = new GenerateEngine(
                processor, moduleBuild, stateStore, executionConfig, tracker, progress, backup);
        return engine.run(initialState, workUnit -> contextFor(byUnitId, workUnit), restriction);
    }

    private UnitPromptFactory promptFactory(String providerId) {
        return new UnitPromptFactory(new PromptTemplateLoader().load(config.prompts(), configBaseDir),
                new PromptRenderer(effectiveMaxPromptChars(providerId)),
                new ContextAssembler(config.context(), sourceCharset()));
    }

    /**
     * {@code aiProvider.providers.<id>.maxPromptChars} when set, else the global
     * {@code context.maxPromptChars} - a locally hosted model can need a far smaller (or
     * larger) context budget than a hosted CLI, and that is a property of the provider.
     */
    int effectiveMaxPromptChars(String providerId) {
        com.devmanchego.jtestforge.config.ProviderConfig providerConfig =
                config.aiProvider().providers().get(providerId);
        Integer override = providerConfig == null ? null : providerConfig.maxPromptChars();
        return override != null ? override : config.context().maxPromptChars();
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
                ResumeReconciler reconciler = new ResumeReconciler(new TestClassScanner(sourceCharset())::testMethodNames);
                var reconciliation = reconciler.reconcile(existing.get(), modulePath, configHash);
                // The loaded state, not reconciliation.state(): reconciling resets each
                // interrupted unit and clears the very lists that say what to revert.
                new com.devmanchego.jtestforge.orchestration.PartialEditReverter(testClassReverter(), modulePath)
                        .revert(existing.get(), reconciliation.unitsNeedingRevert());
                return reconciliation.state();
            }
        }
        return RunState.startNew(UUID.randomUUID().toString(), Instant.now(), Phase.GENERATE,
                        modulePath.toString(), configHash, providerId, springTierState,
                        discovered.stream().map(DiscoveredUnit::workUnit).toList())
                .withBaseline(new Baseline(aggregateLineCoverage(), aggregateBranchCoverage(),
                        null, totalOpenGaps(discovered)));
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
        var testClassInfo = new TestClassScanner(sourceCharset()).scan(discovered.testFile()).orElse(null);
        return new UnitContext(workUnit, discovered.productionClass(), discovered.targetMethod(),
                discovered.testFile(), discovered.testClassSimpleName(), testClassInfo,
                discovered.gaps(), discovered.coverageBefore(), springFacts, frameworkVersions,
                new MockBeanSetResolver().resolve(discovered.productionClass()),
                new TierClassifier().classify(discovered.productionClass()));
    }

    private TestClassLocator testClassLocator() {
        return new TestClassLocator(new TestClassScanner(sourceCharset()),
                modulePath.resolve(config.project().testSourceRoot()),
                config.project().testClassSuffix(), config.project().testClassSuffixByTier());
    }

    private MavenRunner buildMavenRunner() {
        String javaHome = config.project().javaHome();
        ProcessRunner processRunner =
                new ProcessRunner(java.nio.charset.Charset.defaultCharset(), verboseSink, progress);
        List<String> mavenArgs = effectiveMavenArgs();
        return javaHome == null
                ? new MavenRunner(processRunner, config.project().mavenExecutable(), mavenArgs)
                : new MavenRunner(processRunner, config.project().mavenExecutable(), mavenArgs, Path.of(javaHome));
    }

    /**
     * Appends {@code -Djacoco.skip=true} when {@code generate.requireCoverageGain} is off.
     * That flag already keeps JTestForge from ever calling {@code jacoco:report} itself
     * (see {@link #measureBaselineCoverage()}), but it does nothing about a {@code
     * default-instrument}/{@code default-prepare-agent} execution the TARGET module's own
     * pom binds to the ordinary build lifecycle - every {@code test-compile}/{@code test}
     * JTestForge runs would still trigger it and could still fail on a broken JaCoCo setup
     * (found in practice) even though coverage was never going to be consulted anyway.
     */
    private List<String> effectiveMavenArgs() {
        List<String> args = new java.util.ArrayList<>(config.project().mavenArgs());
        if (!config.generate().requireCoverageGain()) {
            args.add("-Djacoco.skip=true");
            if (progress != null) {
                progress.accept("JaCoCo disabled (generate.requireCoverageGain is false)");
            }
        }
        return List.copyOf(args);
    }
}
