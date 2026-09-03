package com.devmanchego.jtestforge.orchestration;

import com.devmanchego.jtestforge.analysis.TestClassMerger;
import com.devmanchego.jtestforge.analysis.TestClassReverter;
import com.devmanchego.jtestforge.analysis.TestClassScanner;
import com.devmanchego.jtestforge.config.ContextConfig;
import com.devmanchego.jtestforge.config.GenerateConfig;
import com.devmanchego.jtestforge.config.SpringConfig;
import com.devmanchego.jtestforge.coverage.CoverageDeltaCalculator;
import com.devmanchego.jtestforge.guard.StaticQualityGuards;
import com.devmanchego.jtestforge.model.ClassCoverage;
import com.devmanchego.jtestforge.model.Collaborator;
import com.devmanchego.jtestforge.model.LineStatus;
import com.devmanchego.jtestforge.model.MethodCoverage;
import com.devmanchego.jtestforge.model.Mutant;
import com.devmanchego.jtestforge.model.MutationReport;
import com.devmanchego.jtestforge.model.MutationStatus;
import com.devmanchego.jtestforge.model.ProductionClass;
import com.devmanchego.jtestforge.model.ProductionMethod;
import com.devmanchego.jtestforge.model.Tier;
import com.devmanchego.jtestforge.model.UnitStatus;
import com.devmanchego.jtestforge.model.Visibility;
import com.devmanchego.jtestforge.model.WorkUnit;
import com.devmanchego.jtestforge.model.WorkUnitId;
import com.devmanchego.jtestforge.prompt.ContextAssembler;
import com.devmanchego.jtestforge.prompt.PromptRenderer;
import com.devmanchego.jtestforge.prompt.PromptTemplateLoader;
import com.devmanchego.jtestforge.provider.RecordedAiProvider;
import com.devmanchego.jtestforge.provider.ResponseParser;
import com.devmanchego.jtestforge.provider.TranscriptWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The real per-unit loop of §9.4, driven by a {@link RecordedAiProvider} and a
 * {@link FakeModuleBuild} - real merger, real reverter, real guards, real response parser,
 * real templates. Only the two genuinely external things (the model and the build) are
 * scripted.
 */
class DefaultUnitProcessorTest {

    private static final String EXISTING_TEST_CLASS = """
            package com.acme;

            import org.junit.jupiter.api.Test;

            class PaymentServiceTest {

                @Test
                void anExistingTest() {
                    assertEquals(1, 1);
                }
            }
            """;

    @TempDir
    Path moduleDir;

    private Path testFile;
    private Path sourceFile;
    private RecordedAiProvider provider;
    private FakeModuleBuild build;

    @BeforeEach
    void setUp() throws IOException {
        testFile = moduleDir.resolve("PaymentServiceTest.java");
        Files.writeString(testFile, EXISTING_TEST_CLASS);
        sourceFile = moduleDir.resolve("PaymentService.java");
        Files.writeString(sourceFile, """
                package com.acme;

                public class PaymentService {
                    public int classify(int amount) {
                        if (amount > 100) {
                            return 2;
                        }
                        return 0;
                    }
                }
                """);
        provider = new RecordedAiProvider("claude");
        build = new FakeModuleBuild();
    }

    @Test
    void aCleanGenerationIsKeptAndReportsItsCoverageDelta() throws IOException {
        provider.enqueueResponse(response("classify_aboveThreshold_returnsTwo",
                "assertThat(subject.classify(500)).isEqualTo(2);"));
        build.compilesSuccessfully(1).scopedTestsPass().coverage(coverageWith(5));

        UnitOutcome outcome = processor().process(context());

        assertThat(outcome.status()).isEqualTo(UnitStatus.DONE);
        assertThat(outcome.addedTests()).containsExactly("classify_aboveThreshold_returnsTwo");
        assertThat(outcome.linesCoveredDelta()).isEqualTo(3);
        assertThat(Files.readString(testFile)).contains("classify_aboveThreshold_returnsTwo");
    }

    @Test
    void aCompileFailureRepairedOnTheSecondAttemptEndsUpKept() throws IOException {
        provider.enqueueResponse(response("firstAttempt", "assertThat(subject.nope()).isEqualTo(2);"))
                .enqueueResponse(response("repairedAttempt", "assertThat(subject.classify(500)).isEqualTo(2);"));
        build.failsToCompile("cannot find symbol: nope")
                .compilesSuccessfully(1)
                .scopedTestsPass()
                .coverage(coverageWith(5));

        UnitOutcome outcome = processor().process(context());

        assertThat(outcome.status()).isEqualTo(UnitStatus.DONE);
        assertThat(outcome.addedTests()).containsExactly("repairedAttempt");
        assertThat(provider.receivedPrompts()).hasSize(2);
        assertThat(provider.receivedPrompts().get(1)).contains("cannot find symbol");
        assertThat(Files.readString(testFile)).doesNotContain("firstAttempt");
    }

    @Test
    void aCompileFailureNeverRepairedRevertsTheFileCompletely() throws IOException {
        String original = Files.readString(testFile);
        provider.enqueueResponse(response("attemptOne", "assertThat(subject.nope()).isEqualTo(2);"))
                .enqueueResponse(response("attemptTwo", "assertThat(subject.stillNope()).isEqualTo(2);"))
                .enqueueResponse(response("attemptThree", "assertThat(subject.nopeAgain()).isEqualTo(2);"));
        build.failsToCompile("cannot find symbol")
                .failsToCompile("cannot find symbol")
                .failsToCompile("cannot find symbol");

        UnitOutcome outcome = processor().process(context());

        assertThat(outcome.status()).isEqualTo(UnitStatus.FAILED_COMPILE);
        assertThat(Files.readString(testFile)).isEqualTo(original);
    }

    @Test
    void anAssertionFailureNeverRepairedRevertsTheFileAndReportsFailedAssertion() throws IOException {
        String original = Files.readString(testFile);
        provider.enqueueResponse(response("wrongExpectation", "assertThat(subject.classify(500)).isEqualTo(9);"))
                .enqueueResponse(response("stillWrong", "assertThat(subject.classify(500)).isEqualTo(8);"))
                .enqueueResponse(response("wrongAgain", "assertThat(subject.classify(500)).isEqualTo(7);"));
        build.compilesSuccessfully(5)
                .scopedTestsFail("expected 9 but was 2")
                .scopedTestsFail("expected 8 but was 2")
                .scopedTestsFail("expected 7 but was 2");

        UnitOutcome outcome = processor().process(context());

        assertThat(outcome.status()).isEqualTo(UnitStatus.FAILED_ASSERTION);
        assertThat(Files.readString(testFile)).isEqualTo(original);
    }

    @Test
    void zeroCoverageDeltaOnAPlainUnitDiscardsTheTestsAndRevertsTheFile() throws IOException {
        String original = Files.readString(testFile);
        provider.enqueueResponse(response("coversNothingNew", "assertThat(subject.classify(500)).isEqualTo(2);"));
        build.compilesSuccessfully(1).scopedTestsPass().coverage(coverageWith(2));

        UnitOutcome outcome = processor().process(context());

        assertThat(outcome.status()).isEqualTo(UnitStatus.DISCARDED_NO_VALUE);
        assertThat(Files.readString(testFile)).isEqualTo(original);
    }

    @Test
    void aResponseWhoseEveryCandidateIsRejectedByAGuardWritesNothing() throws IOException {
        String original = Files.readString(testFile);
        provider.enqueueResponse(response("assertsNothingAtAll", "subject.classify(500);"));

        UnitOutcome outcome = processor().process(context());

        assertThat(outcome.status()).isEqualTo(UnitStatus.DISCARDED_NO_VALUE);
        assertThat(outcome.discarded()).anySatisfy(reason -> assertThat(reason).contains("NO_ASSERTION"));
        assertThat(Files.readString(testFile)).isEqualTo(original);
    }

    @Test
    void aTransportFailureIsReportedAsProviderErrorWithoutTouchingTheFile() throws IOException {
        String original = Files.readString(testFile);
        provider.enqueueFailure("the CLI produced no output");

        UnitOutcome outcome = processor().process(context());

        assertThat(outcome.status()).isEqualTo(UnitStatus.PROVIDER_ERROR);
        assertThat(outcome.error()).contains("no output");
        assertThat(Files.readString(testFile)).isEqualTo(original);
    }

    @Test
    void aResponseViolatingTheContractIsRecordedAndWritesNothing() throws IOException {
        String original = Files.readString(testFile);
        provider.enqueueResponse("I could not write those tests, sorry.");

        UnitOutcome outcome = processor().process(context());

        assertThat(outcome.status()).isEqualTo(UnitStatus.DISCARDED_NO_VALUE);
        assertThat(outcome.discarded()).anySatisfy(reason ->
                assertThat(reason).contains("response contract violation"));
        assertThat(Files.readString(testFile)).isEqualTo(original);
    }

    @Test
    void everyPromptAndResponseIsWrittenToTheTranscript() throws IOException {
        provider.enqueueResponse(response("aTest", "assertThat(subject.classify(500)).isEqualTo(2);"));
        build.compilesSuccessfully(1).scopedTestsPass().coverage(coverageWith(5));

        processor().process(context());

        Path transcripts = moduleDir.resolve("state").resolve("transcripts");
        try (var walk = Files.walk(transcripts)) {
            assertThat(walk.filter(p -> p.getFileName().toString().endsWith(".prompt.md")))
                    .as("the prompt is the single most valuable artifact for tuning (§12.2)")
                    .isNotEmpty();
        }
    }

    // --- §7.6: the ESCALATION_REQUIRED path end to end ---------------------------------

    @Test
    void anEscalationSynthesisesTheMissingMockBeanAndTheRetrySucceeds() throws IOException {
        Files.writeString(testFile, dataSliceSkeleton());
        String escalatingCandidate = wrapInFence("""
                @Test
                void settle_recordsAudit() {
                    when(auditLog.record("INV-1")).thenReturn(true);
                    assertThat(paymentService.settle("INV-1")).isNotNull();
                }
                """);
        // The same candidate is queued twice: it is invalid before the escalation (auditLog
        // is not yet a declared mock bean) and valid after, once synthesis adds the field -
        // nothing about the candidate itself needs to change.
        provider.enqueueResponse(escalatingCandidate).enqueueResponse(escalatingCandidate);
        build.compilesSuccessfully(2).scopedTestsPass();

        UnitOutcome outcome = processor(SpringGenerationSupport.forNewRun(40),
                new GenerateConfig(null, 2, false, null, null)).process(dataSliceContext());

        assertThat(outcome.status()).isEqualTo(UnitStatus.DONE);
        assertThat(outcome.addedTests()).containsExactly("settle_recordsAudit");
        assertThat(provider.receivedPrompts()).hasSize(2);
        String finalSource = Files.readString(testFile);
        assertThat(finalSource).contains("@MockitoBean").contains("private AuditLog auditLog;");
    }

    @Test
    void aSecondEscalationOnTheSameClassFailsTerminallyWithoutARetry() throws IOException {
        Files.writeString(testFile, dataSliceSkeleton());
        String escalatingCandidate = wrapInFence("""
                @Test
                void settle_recordsAudit() {
                    when(auditLog.record("INV-1")).thenReturn(true);
                    assertThat(paymentService.settle("INV-1")).isNotNull();
                }
                """);
        provider.enqueueResponse(escalatingCandidate);
        SpringGenerationSupport springSupport = SpringGenerationSupport.forNewRun(40);
        // Simulates this test class having already spent its one allowed escalation
        // earlier in the same run (§7.6: once per class, or a second candidate could keep
        // re-forking the context cache key indefinitely).
        springSupport.mockBeanEscalation().recordEscalation("com.acme.PaymentServiceTest");

        UnitOutcome outcome = processor(springSupport, new GenerateConfig(null, 2, false, null, null))
                .process(dataSliceContext());

        assertThat(outcome.status()).isEqualTo(UnitStatus.ESCALATION_REQUIRED);
        assertThat(outcome.error()).contains("already re-synthesised once this run");
        assertThat(provider.receivedPrompts()).hasSize(1);
    }

    // --- §10.1 step 5: pass 2's mutant-kill acceptance gate ----------------------------

    @Test
    void aTestThatKillsAPreviouslySurvivingMutantIsKept() throws IOException {
        Mutant target = survivingMutant("classify", 6, "MathMutator");
        provider.enqueueResponse(response("classify_killsTheMutant",
                "assertThat(subject.classify(500)).isEqualTo(2);"));
        build.compilesSuccessfully(1).scopedTestsPass();
        FakeMutationRunner mutationRunner = new FakeMutationRunner()
                .thenReturns(new MutationReport(List.of(killedVariantOf(target))));

        UnitOutcome outcome = processorForMutation(mutationRunner)
                .process(mutationContext(target));

        assertThat(outcome.status()).isEqualTo(UnitStatus.DONE);
        assertThat(outcome.mutantsKilled()).containsExactly(target.stableId());
        assertThat(Files.readString(testFile)).contains("classify_killsTheMutant");
    }

    @Test
    void aTestThatKillsNothingIsRevertedAndDiscarded() throws IOException {
        String original = Files.readString(testFile);
        Mutant target = survivingMutant("classify", 6, "MathMutator");
        provider.enqueueResponse(response("classify_missesTheMutant",
                "assertThat(subject.classify(500)).isEqualTo(2);"));
        build.compilesSuccessfully(1).scopedTestsPass();
        // The scoped re-run reports the SAME mutant still SURVIVED - the merged test
        // compiled and passed, but proved nothing PIT did not already know.
        FakeMutationRunner mutationRunner = new FakeMutationRunner()
                .thenReturns(new MutationReport(List.of(target)));

        UnitOutcome outcome = processorForMutation(mutationRunner)
                .process(mutationContext(target));

        assertThat(outcome.status()).isEqualTo(UnitStatus.DISCARDED_NO_VALUE);
        assertThat(outcome.mutantsKilled()).isEmpty();
        assertThat(Files.readString(testFile)).isEqualTo(original);
    }

    // --- fixtures ---------------------------------------------------------------------

    private DefaultUnitProcessor processor() {
        return processor(SpringGenerationSupport.forNewRun(40), generateConfig());
    }

    private DefaultUnitProcessor processor(SpringGenerationSupport springSupport) {
        return processor(springSupport, generateConfig());
    }

    private DefaultUnitProcessor processor(SpringGenerationSupport springSupport, GenerateConfig config) {
        var templates = new PromptTemplateLoader().loadBundled();
        var assembler = new ContextAssembler(new ContextConfig(null, null, null, null, null, null, null));
        return new DefaultUnitProcessor(
                provider,
                new TranscriptWriter(moduleDir.resolve("state")),
                new UnitPromptFactory(templates, new PromptRenderer(200_000), assembler),
                new ResponseParser(),
                new StaticQualityGuards(config, springConfig()),
                new TestClassMerger(),
                new TestClassReverter(),
                build,
                new CoverageAndGapAcceptanceGate(build, new CoverageDeltaCalculator(), new ValueGate(config)),
                config,
                Duration.ofSeconds(30),
                springSupport);
    }

    private DefaultUnitProcessor processorForMutation(FakeMutationRunner mutationRunner) {
        var templates = new PromptTemplateLoader().loadBundled();
        var assembler = new ContextAssembler(new ContextConfig(null, null, null, null, null, null, null));
        GenerateConfig config = generateConfig();
        return new DefaultUnitProcessor(
                provider,
                new TranscriptWriter(moduleDir.resolve("state")),
                new UnitPromptFactory(templates, new PromptRenderer(200_000), assembler),
                new ResponseParser(),
                new StaticQualityGuards(config, springConfig()),
                new TestClassMerger(),
                new TestClassReverter(),
                build,
                new MutationAcceptanceGate(mutationRunner, moduleDir, moduleDir.resolve("pit-history.bin"),
                        "DEFAULTS", Duration.ofSeconds(30)),
                config,
                Duration.ofSeconds(30),
                SpringGenerationSupport.forNewRun(40));
    }

    private GenerateConfig generateConfig() {
        return new GenerateConfig(null, 2, null, null, null);
    }

    private SpringConfig springConfig() {
        return new SpringConfig(null, null, null, null, null, null, null, null, null, null, null);
    }

    private UnitContext context() {
        // One int parameter, to join against the "(I)I" descriptor in coverageWith() -
        // CoverageMethodJoiner matches on name AND parameter types.
        ProductionMethod classify = new ProductionMethod("classify", "int",
                List.of(new com.devmanchego.jtestforge.model.ProductionParameter("amount", "int", List.of())),
                Visibility.PUBLIC, false, List.of(), Map.of(), List.of(), 4, 9, 2);
        ProductionClass productionClass = new ProductionClass("com.acme.PaymentService", sourceFile,
                List.of(), List.of(), List.of(new Collaborator("gateway", "com.acme.PaymentGateway")),
                List.of(classify));
        WorkUnit unit = WorkUnit.pending(
                WorkUnitId.of("com.acme.PaymentService", "classify(int)", Tier.PLAIN_UNIT),
                testFile.toString(), sourceFile.toString(), "sha256:src");

        return new UnitContext(unit, productionClass, classify, testFile, "PaymentServiceTest",
                new TestClassScanner().scan(testFile).orElseThrow(), List.of(),
                coverageWith(2), null, null, List.of(), null);
    }

    /** A pass-2 unit targeting one mutant, otherwise shaped like {@link #context()}. */
    private UnitContext mutationContext(Mutant target) {
        ProductionMethod classify = new ProductionMethod("classify", "int",
                List.of(new com.devmanchego.jtestforge.model.ProductionParameter("amount", "int", List.of())),
                Visibility.PUBLIC, false, List.of(), Map.of(), List.of(), 4, 9, 2);
        ProductionClass productionClass = new ProductionClass("com.acme.PaymentService", sourceFile,
                List.of(), List.of(), List.of(new Collaborator("gateway", "com.acme.PaymentGateway")),
                List.of(classify));
        WorkUnitId id = WorkUnitId.of("com.acme.PaymentService", "classify(int)", Tier.PLAIN_UNIT)
                .withMutantGroup(target.mutator() + "@" + target.lineNumber());
        WorkUnit unit = WorkUnit.pending(id, testFile.toString(), sourceFile.toString(), "sha256:src");

        return new UnitContext(unit, productionClass, classify, testFile, "PaymentServiceTest",
                new TestClassScanner().scan(testFile).orElseThrow(), List.of(), null, null, null, List.of(), null,
                List.of(target));
    }

    private Mutant survivingMutant(String method, int line, String mutatorSimpleName) {
        return new Mutant("com.acme.PaymentService", method, "(I)I", line,
                "org.pitest.mutationtest.engine.gregor.mutators." + mutatorSimpleName,
                List.of(1), MutationStatus.SURVIVED, null, "description");
    }

    private Mutant killedVariantOf(Mutant mutant) {
        return new Mutant(mutant.mutatedClass(), mutant.mutatedMethod(), mutant.methodDescription(),
                mutant.lineNumber(), mutant.mutator(), mutant.indexes(), MutationStatus.KILLED,
                "com.acme.PaymentServiceTest.classify_killsTheMutant", mutant.description());
    }

    /** A coverage snapshot for {@code classify} with the given number of covered lines. */
    private ClassCoverage coverageWith(int linesCovered) {
        return new ClassCoverage("com.acme.PaymentService", 0, 0, 0, linesCovered, 0, 0,
                List.of(new MethodCoverage("classify", "(I)I", 4, 0, 0, 0, linesCovered, 0, 0)),
                Map.of(5, new LineStatus(1, 0, 0, 0)));
    }

    private String response(String methodName, String body) {
        return """
                ```java
                @Test
                void %s() {
                    %s
                }
                ```
                """.formatted(methodName, body);
    }

    private String wrapInFence(String methodSource) {
        return "```java\n" + methodSource + "```\n";
    }

    private String dataSliceSkeleton() {
        return """
                package com.acme;

                import org.springframework.beans.factory.annotation.Autowired;
                import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
                import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

                @DataJpaTest
                class PaymentServiceTest {

                    @Autowired
                    private TestEntityManager entityManager;

                    @Autowired
                    private PaymentService paymentService;
                }
                """;
    }

    /** A DATA_SLICE unit whose production class has a collaborator not yet mocked. */
    private UnitContext dataSliceContext() {
        ProductionMethod settle = new ProductionMethod("settle",
                "com.acme.Receipt", List.of(new com.devmanchego.jtestforge.model.ProductionParameter(
                        "reference", "java.lang.String", List.of())),
                Visibility.PUBLIC, false, List.of(), Map.of(), List.of(), 4, 9, 2);
        ProductionClass productionClass = new ProductionClass("com.acme.PaymentService", sourceFile,
                List.of(), List.of(),
                List.of(new Collaborator("gateway", "com.acme.PaymentGateway"),
                        new Collaborator("auditLog", "com.acme.audit.AuditLog")),
                List.of(settle));
        WorkUnit unit = WorkUnit.pending(
                WorkUnitId.of("com.acme.PaymentService", "settle(String)", Tier.DATA_SLICE),
                testFile.toString(), sourceFile.toString(), "sha256:src");
        var springFacts = new com.devmanchego.jtestforge.model.SpringStackFacts(
                true, true, null, null, false, true, false,
                com.devmanchego.jtestforge.model.SpringStackFacts.DetectedEmbeddedDatabase.H2,
                com.devmanchego.jtestforge.model.SpringStackFacts.ValidationApi.NONE,
                "org.springframework.test.context.bean.override.mockito.MockitoBean", false);

        return new UnitContext(unit, productionClass, settle, testFile, "PaymentServiceTest",
                new TestClassScanner().scan(testFile).orElseThrow(), List.of(),
                null, springFacts, null, List.of(), null);
    }
}
