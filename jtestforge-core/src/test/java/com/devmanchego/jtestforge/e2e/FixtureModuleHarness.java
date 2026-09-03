package com.devmanchego.jtestforge.e2e;

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
import com.devmanchego.jtestforge.config.ContextConfig;
import com.devmanchego.jtestforge.config.ExecutionConfig;
import com.devmanchego.jtestforge.config.GenerateConfig;
import com.devmanchego.jtestforge.config.JTestForgeConfig;
import com.devmanchego.jtestforge.config.SpringConfig;
import com.devmanchego.jtestforge.coverage.CoverageDeltaCalculator;
import com.devmanchego.jtestforge.coverage.JacocoReportParser;
import com.devmanchego.jtestforge.guard.StaticQualityGuards;
import com.devmanchego.jtestforge.model.Baseline;
import com.devmanchego.jtestforge.model.ClassCoverage;
import com.devmanchego.jtestforge.model.Phase;
import com.devmanchego.jtestforge.model.ProductionClass;
import com.devmanchego.jtestforge.model.RunState;
import com.devmanchego.jtestforge.model.SpringStackFacts;
import com.devmanchego.jtestforge.model.SpringTierState;
import com.devmanchego.jtestforge.model.Tier;
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
import com.devmanchego.jtestforge.spring.ContextKeyStabilityTracker;
import com.devmanchego.jtestforge.spring.SpringStackDetector;
import com.devmanchego.jtestforge.state.StateStore;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Stands the whole real pipeline up against a working copy of the Spring Boot fixture
 * module — jtestforge-implementation-plan.md phase 18.
 *
 * <p>Real {@code mvn}, real Spring, real JaCoCo. Only the AI is scripted
 * ({@link PromptRoutingAiProvider}), which is the single thing that cannot be both real
 * and deterministic. Everything the earlier phases only asserted against recorded fixtures
 * - method descriptors, coverage line ranges, context cache keys, report shapes - is
 * exercised here against the tools that actually produce them.
 *
 * <p>Every run works on a <b>copy</b> of the fixture in a temp directory: {@code generate}
 * writes to the module's test sources by design, and mutating the checked-in fixture would
 * make each test depend on whichever one ran before it.
 */
final class FixtureModuleHarness {

    static final Path CHECKED_IN_FIXTURE =
            Path.of("src/test/resources/spring-fixture-module").toAbsolutePath().normalize();

    private static final Duration BUILD_TIMEOUT = Duration.ofMinutes(5);
    private static final List<String> MAVEN_ARGS = List.of("-o", "-B");

    private final Path moduleDir;
    private final Path stateDir;
    private final JTestForgeConfig config;

    private final MavenRunner mavenRunner;
    private final ModuleBuild moduleBuild;
    private final StateStore stateStore;

    private List<ProductionClass> productionClasses;
    private Map<String, ClassCoverage> baselineCoverage;

    FixtureModuleHarness(Path moduleDir) {
        this.moduleDir = moduleDir;
        this.stateDir = moduleDir.resolve(".jtestforge");
        this.config = defaultConfig();
        this.mavenRunner = new MavenRunner(new com.devmanchego.jtestforge.util.ProcessRunner(),
                "mvn", MAVEN_ARGS);
        this.moduleBuild = new MavenModuleBuild(mavenRunner, moduleDir, BUILD_TIMEOUT);
        this.stateStore = new StateStore(stateDir, Clock.systemUTC());
    }

    /** Copies the checked-in fixture into {@code target}, excluding any previous build output. */
    static void copyFixtureTo(Path target) {
        try (Stream<Path> walk = Files.walk(CHECKED_IN_FIXTURE)) {
            for (Path source : walk.toList()) {
                Path relative = CHECKED_IN_FIXTURE.relativize(source);
                if (relative.toString().startsWith("target") || relative.toString().startsWith(".jtestforge")) {
                    continue;
                }
                Path destination = target.resolve(relative);
                if (Files.isDirectory(source)) {
                    Files.createDirectories(destination);
                } else {
                    Files.createDirectories(destination.getParent());
                    Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to copy the fixture module to " + target, e);
        }
    }

    /**
     * §9 steps 1-2: build the module green once, measure coverage, and scan its sources.
     * Done once per harness because it is the expensive part and none of it changes
     * between the runs a single test performs.
     */
    void preflightAndBaseline() {
        var result = mavenRunner.run(moduleDir, List.of("clean", "test", "jacoco:report"), BUILD_TIMEOUT);
        if (!result.succeeded()) {
            throw new IllegalStateException("The fixture module must build green before a run (§2):\n"
                    + result.stdout() + result.stderr());
        }
        this.baselineCoverage = readCoverage();
        this.productionClasses = scanProductionClasses();
    }

    Map<String, ClassCoverage> baselineCoverage() {
        return baselineCoverage;
    }

    /** Re-reads coverage after a run, so a delta can be measured against the baseline. */
    Map<String, ClassCoverage> measureCoverageNow() {
        var result = mavenRunner.run(moduleDir, List.of("test", "jacoco:report"), BUILD_TIMEOUT);
        if (!result.succeeded()) {
            throw new IllegalStateException("Post-run measurement build failed:\n"
                    + result.stdout() + result.stderr());
        }
        return readCoverage();
    }

    List<DiscoveredUnit> discoverUnits() {
        WorkUnitDiscovery discovery = new WorkUnitDiscovery(config, testClassLocator());
        return discovery.discover(new DiscoveryInputs(productionClasses, baselineCoverage,
                springTierState(), springFacts(), TestFrameworkVersions.none()));
    }

    /**
     * Runs pass 1 exactly as production would, against the real module.
     *
     * @param tracker shared with the processor so the test can assert the run's real
     *                context-load count (§9.6) rather than infer it
     */
    GenerateResult runGenerate(AiProvider provider, TierRestriction restriction,
                               ContextKeyStabilityTracker tracker) {
        return runGenerate(provider, restriction, tracker, 0);
    }

    /**
     * @param maxUnitsPerRun 0 for unlimited; a positive value stops the run early, which
     *                       is how the resume test reproduces an interrupted run's state
     *                       without actually killing the JVM
     */
    GenerateResult runGenerate(AiProvider provider, TierRestriction restriction,
                               ContextKeyStabilityTracker tracker, int maxUnitsPerRun) {
        List<DiscoveredUnit> discovered = discoverUnits();
        Map<String, DiscoveredUnit> byUnitId = new LinkedHashMap<>();
        for (DiscoveredUnit unit : discovered) {
            byUnitId.put(unit.workUnit().id().format(), unit);
        }

        RunState initialState = existingStateOr(discovered);

        SpringGenerationSupport springSupport = new SpringGenerationSupport(
                new com.devmanchego.jtestforge.spring.ContextKeyGuard(),
                new com.devmanchego.jtestforge.spring.MockBeanEscalation(),
                new com.devmanchego.jtestforge.analysis.MockBeanSynthesizer(),
                new com.devmanchego.jtestforge.spring.MockBeanSetResolver(),
                new com.devmanchego.jtestforge.spring.ContextKeyModel(),
                tracker);

        DefaultUnitProcessor processor = new DefaultUnitProcessor(
                provider,
                new TranscriptWriter(stateDir),
                new UnitPromptFactory(new PromptTemplateLoader().loadBundled(),
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
                Duration.ofSeconds(60),
                springSupport);

        ExecutionConfig executionConfig = maxUnitsPerRun > 0
                ? new ExecutionConfig(stateDir.toString(), null, null, null, maxUnitsPerRun)
                : config.execution();
        GenerateEngine engine = new GenerateEngine(processor, moduleBuild, stateStore,
                executionConfig, tracker);

        return engine.run(initialState, workUnit -> contextFor(byUnitId, workUnit), restriction);
    }

    /** Resumes an existing run rather than rediscovering, when a state file is already present. */
    private RunState existingStateOr(List<DiscoveredUnit> discovered) {
        return stateStore.load().orElseGet(() -> RunState.startNew(
                "e2e-run", Instant.now(), Phase.GENERATE, moduleDir.toString(), "sha256:e2e", "scripted",
                springTierState(), discovered.stream().map(DiscoveredUnit::workUnit).toList())
                .withBaseline(new Baseline(0.0, 0.0, null, totalOpenGaps(discovered))));
    }

    private int totalOpenGaps(List<DiscoveredUnit> discovered) {
        return discovered.stream().mapToInt(unit -> unit.gaps().size()).sum();
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
                discovered.gaps(), discovered.coverageBefore(), springFacts(),
                TestFrameworkVersions.none(),
                new com.devmanchego.jtestforge.spring.MockBeanSetResolver()
                        .resolve(discovered.productionClass()),
                new com.devmanchego.jtestforge.spring.TierClassifier().classify(discovered.productionClass()));
    }

    StateStore stateStore() {
        return stateStore;
    }

    Path moduleDir() {
        return moduleDir;
    }

    // --- module analysis -----------------------------------------------------------

    private List<ProductionClass> scanProductionClasses() {
        Path mainSourceRoot = moduleDir.resolve(config.project().mainSourceRoot());
        List<Path> classpath = new MavenClasspathResolver(
                new com.devmanchego.jtestforge.util.ProcessRunner(), "mvn", MAVEN_ARGS)
                .resolveCompileClasspath(moduleDir, BUILD_TIMEOUT);
        var typeSolver = ProductionTypeSolvers.forModule(mainSourceRoot, classpath);
        return new ProductionClassScanner(typeSolver, mainSourceRoot).scan();
    }

    private Map<String, ClassCoverage> readCoverage() {
        Path report = moduleDir.resolve("target/site/jacoco/jacoco.xml");
        if (!Files.isRegularFile(report)) {
            throw new IllegalStateException("No JaCoCo report at " + report);
        }
        Map<String, ClassCoverage> byFqn = new LinkedHashMap<>();
        for (ClassCoverage coverage : new JacocoReportParser().parse(report)) {
            byFqn.put(coverage.fqn(), coverage);
        }
        return Map.copyOf(byFqn);
    }

    private TestClassLocator testClassLocator() {
        return new TestClassLocator(new TestClassScanner(),
                moduleDir.resolve(config.project().testSourceRoot()),
                config.project().testClassSuffix(), config.project().testClassSuffixByTier());
    }

    private SpringStackFacts springFacts() {
        // The fixture is a real Spring Boot 3.2.5 module: Framework 6.1, so @MockBean, not
        // @MockitoBean (which arrives with Framework 6.2 / Boot 3.4). Stated explicitly
        // rather than detected so the assertion is about the generator, not the detector -
        // SpringStackDetector has its own tests.
        return new SpringStackFacts(true, true,
                new com.devmanchego.jtestforge.model.SemanticVersion(6, 1, 6),
                new com.devmanchego.jtestforge.model.SemanticVersion(3, 2, 5),
                true, false, false,
                SpringStackFacts.DetectedEmbeddedDatabase.NONE,
                SpringStackFacts.ValidationApi.JAKARTA,
                "org.springframework.boot.test.mock.mockito.MockBean", false);
    }

    private SpringTierState springTierState() {
        return new SpringStackDetector().resolveTierAvailability(springFacts(), config.spring());
    }

    private JTestForgeConfig defaultConfig() {
        return new JTestForgeConfig(
                new com.devmanchego.jtestforge.config.ProjectConfig(
                        moduleDir.toString(), "mvn", MAVEN_ARGS, null, null, null, null, null),
                new com.devmanchego.jtestforge.config.SelectionConfig(
                        null, null, null, null, null, null, null),
                null, null,
                new SpringConfig(null, null, null, null, null, null, null, null, null, null, null),
                new ContextConfig(null, null, null, null, null, null, null),
                new GenerateConfig(null, null, null, null, null),
                null,
                new ExecutionConfig(stateDir.toString(), null, null, null, null));
    }
}
