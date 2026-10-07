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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    /**
     * The delta is only measured (jacoco:report only invoked) when requireCoverageGain is
     * on - see {@link #withRequireCoverageGainOffByDefaultAZeroDeltaCandidateIsKeptAnyway()}
     * for the default, where a real improvement would go unreported by design.
     */
    @Test
    void aCleanGenerationIsKeptAndReportsItsCoverageDeltaWhenRequireCoverageGainIsOn() throws IOException {
        provider.enqueueResponse(response("classify_aboveThreshold_returnsTwo",
                "assertThat(subject.classify(500)).isEqualTo(2);"));
        build.compilesSuccessfully(1).scopedTestsPass().coverage(coverageWith(5));
        GenerateConfig strict = new GenerateConfig(null, 2, true, null, null);

        UnitOutcome outcome = processor(SpringGenerationSupport.forNewRun(40), strict).process(context());

        assertThat(outcome.status()).isEqualTo(UnitStatus.DONE);
        assertThat(outcome.addedTests()).containsExactly("classify_aboveThreshold_returnsTwo");
        assertThat(outcome.linesCoveredDelta()).isEqualTo(3);
        assertThat(Files.readString(testFile)).contains("classify_aboveThreshold_returnsTwo");
    }

    @Test
    void anExistingTestFileThatCannotBeParsedIsNeverOverwrittenWithASkeleton() throws IOException {
        // Discovery already skips such a unit; this pins the processor's own last line of
        // defence, for a file that turns unreadable after discovery looked at it.
        String brokenButPrecious = """
                package com.acme;

                class PaymentServiceTest {
                    @Test
                    void aDeveloperWroteThis() { this no longer parses }
                }
                """;
        UnitContext withoutTestClassInfo = context().withTestClassInfo(null);
        Files.writeString(testFile, brokenButPrecious);

        UnitOutcome outcome = processor().process(withoutTestClassInfo);

        assertThat(outcome.status()).isEqualTo(UnitStatus.SKIPPED_TEST_FILE_UNREADABLE);
        assertThat(outcome.error()).contains("PaymentServiceTest.java").containsIgnoringCase("parse");
        assertThat(Files.readString(testFile)).isEqualTo(brokenButPrecious);
        assertThat(provider.receivedPrompts()).isEmpty();
    }

    // --- phase 3: write-ahead journal -----------------------------------------------------------

    @Test
    void everyMergeIsJournaledBeforeItIsWrittenAndEveryRevertClearsTheJournalAfterwards() throws IOException {
        provider.enqueueResponse(response("wrongExpectation", "assertThat(subject.classify(500)).isEqualTo(9);"))
                .enqueueResponse(response("fixedExpectation", "assertThat(subject.classify(500)).isEqualTo(2);"));
        build.compilesSuccessfully(2)
                .scopedTestsFail("expected 9 but was 2")
                .scopedTestsPass()
                .coverage(coverageWith(5));
        List<String> journal = new java.util.ArrayList<>();

        UnitOutcome outcome = processor().process(context(), (addedTests, addedImports) -> {
            String file = readUnchecked(testFile);
            journal.add(addedTests + " wrongInFile=" + file.contains("wrongExpectation")
                    + " fixedInFile=" + file.contains("fixedExpectation"));
        });

        assertThat(outcome.status()).isEqualTo(UnitStatus.DONE);
        assertThat(journal).containsExactly(
                // recorded before the merge reached the file
                "[wrongExpectation] wrongInFile=false fixedInFile=false",
                // cleared only once the revert had already taken it out
                "[] wrongInFile=false fixedInFile=false",
                "[fixedExpectation] wrongInFile=false fixedInFile=false");
    }

    @Test
    void anUnexpectedExceptionAlsoLeavesTheJournalEmptyOnceTheFileIsRestored() throws IOException {
        provider.enqueueResponse(response("classify_aboveThreshold_returnsTwo",
                "assertThat(subject.classify(500)).isEqualTo(2);"));
        build.compilesSuccessfully(1).scopedRunThrows(new IllegalStateException("maven was interrupted"));
        List<List<String>> journal = new java.util.ArrayList<>();

        assertThatThrownBy(() -> processor().process(context(), (addedTests, addedImports) -> journal.add(addedTests)))
                .isInstanceOf(IllegalStateException.class);

        assertThat(journal).containsExactly(List.of("classify_aboveThreshold_returnsTwo"), List.of());
    }

    private static String readUnchecked(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    // --- phase 2 / 2b: nothing survives a failure, whichever way the unit ends ----------------

    @Test
    void anUnexpectedExceptionAfterMergingStillRestoresTheFileAndIsRethrown() throws IOException {
        String original = Files.readString(testFile);
        provider.enqueueResponse(response("classify_aboveThreshold_returnsTwo",
                "assertThat(subject.classify(500)).isEqualTo(2);"));
        build.compilesSuccessfully(1).scopedRunThrows(new IllegalStateException("maven was interrupted"));

        assertThatThrownBy(() -> processor().process(context()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("maven was interrupted");

        assertThat(Files.readString(testFile)).isEqualTo(original);
    }

    @Test
    void aSkeletonTheUnitCreatedIsDeletedWhenTheUnitIsDiscarded() throws IOException {
        // Observed in a real run: a discarded unit left an empty OrderServiceTest behind.
        provider.enqueueResponse("I could not think of any test for this method.")
                .enqueueResponse("Still no tests, sorry.");
        UnitContext noTestClassYet = context().withTestClassInfo(null);
        Files.delete(testFile);

        UnitOutcome outcome = processor().process(noTestClassYet);

        assertThat(outcome.status()).isEqualTo(UnitStatus.DISCARDED_NO_VALUE);
        assertThat(testFile).doesNotExist();
    }

    @Test
    void aSkeletonTheUnitCreatedIsDeletedWhenAnUnexpectedExceptionEndsTheUnit() throws IOException {
        provider.enqueueResponse(response("classify_aboveThreshold_returnsTwo",
                "assertThat(subject.classify(500)).isEqualTo(2);"));
        build.compilesSuccessfully(1).scopedRunThrows(new IllegalStateException("maven was interrupted"));
        UnitContext noTestClassYet = context().withTestClassInfo(null);
        Files.delete(testFile);

        assertThatThrownBy(() -> processor().process(noTestClassYet)).isInstanceOf(IllegalStateException.class);

        assertThat(testFile).doesNotExist();
    }

    @Test
    void aSkeletonTheUnitCreatedIsKeptWhenTheUnitSucceeds() throws IOException {
        provider.enqueueResponse(response("classify_aboveThreshold_returnsTwo",
                "assertThat(subject.classify(500)).isEqualTo(2);"));
        build.compilesSuccessfully(1).scopedTestsPass().coverage(coverageWith(5));
        UnitContext noTestClassYet = context().withTestClassInfo(null);
        Files.delete(testFile);

        UnitOutcome outcome = processor().process(noTestClassYet);

        assertThat(outcome.status()).isEqualTo(UnitStatus.DONE);
        assertThat(Files.readString(testFile)).contains("classify_aboveThreshold_returnsTwo");
    }

    @Test
    void aTestClassThatAlreadyExistedIsNeverDeletedWhenTheUnitFails() throws IOException {
        provider.enqueueResponse("I could not think of any test for this method.")
                .enqueueResponse("Still no tests, sorry.");

        UnitOutcome outcome = processor().process(context());

        assertThat(outcome.status()).isEqualTo(UnitStatus.DISCARDED_NO_VALUE);
        assertThat(Files.readString(testFile)).isEqualTo(EXISTING_TEST_CLASS);
    }

    @Test
    void theProcessorIsReusableAfterAnExceptionWithNoStateLeakingIntoTheNextUnit() throws IOException {
        // The edits record lives in a field; a unit that threw must not leave its batch or its
        // "I created the file" flag behind for the next unit to act on.
        provider.enqueueResponse(response("firstUnit", "assertThat(subject.classify(500)).isEqualTo(2);"));
        build.compilesSuccessfully(1).scopedRunThrows(new IllegalStateException("boom"));
        DefaultUnitProcessor processor = processor();
        UnitContext noTestClassYet = context().withTestClassInfo(null);
        Files.delete(testFile);
        assertThatThrownBy(() -> processor.process(noTestClassYet)).isInstanceOf(IllegalStateException.class);

        Files.writeString(testFile, EXISTING_TEST_CLASS);
        provider.enqueueResponse("nothing useful").enqueueResponse("still nothing useful");
        UnitOutcome second = processor.process(context());

        assertThat(second.status()).isEqualTo(UnitStatus.DISCARDED_NO_VALUE);
        assertThat(Files.readString(testFile)).isEqualTo(EXISTING_TEST_CLASS);
    }

    @Test
    void testsWhoseBodiesAreCopiesOfAnotherTestInTheResponseAreDroppedAndRecorded() throws IOException {
        // Wiring check for guard 12: it is a batch-level guard, and a guard nothing calls
        // protects nothing (SemanticDuplicateGuard is exactly that). Three tests, one body.
        provider.enqueueResponse("""
                ```java
                @Test
                void classify_aboveThreshold_returnsTwo() {
                    assertThat(subject.classify(500)).isEqualTo(2);
                }

                @Test
                void classify_exactlyAtThreshold_returnsTwo() {
                    assertThat(subject.classify(500)).isEqualTo(2);
                }

                @Test
                void classify_wellAboveThreshold_returnsTwo() {
                    assertThat(subject.classify(500)).isEqualTo(2);
                }
                ```
                """);
        build.compilesSuccessfully(1).scopedTestsPass().coverage(coverageWith(5));

        UnitOutcome outcome = processor().process(context());

        assertThat(outcome.status()).isEqualTo(UnitStatus.DONE);
        assertThat(outcome.addedTests()).containsExactly("classify_aboveThreshold_returnsTwo");
        assertThat(outcome.discarded()).anySatisfy(entry -> assertThat(entry)
                .contains("DUPLICATE_BODY").contains("classify_exactlyAtThreshold_returnsTwo"));
        assertThat(outcome.discarded()).anySatisfy(entry -> assertThat(entry)
                .contains("DUPLICATE_BODY").contains("classify_wellAboveThreshold_returnsTwo"));
        assertThat(Files.readString(testFile))
                .contains("classify_aboveThreshold_returnsTwo")
                .doesNotContain("classify_exactlyAtThreshold_returnsTwo");
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

    /**
     * A build that fails for a reason {@code CompilerErrorParser} does not recognise (an
     * annotation processor, a plugin execution, anything that is not a plain javac
     * diagnostic) must not leave the model with an empty "Compiler errors" section - it
     * gets the raw build log instead, and that log is also kept on disk for a human.
     */
    @Test
    void aCompileFailureTheParserCannotExplainStillGivesTheModelTheRawLog() throws IOException {
        String rawLog = "Exit code: 1\n\n--- stdout ---\n[ERROR] some plugin blew up\n--- stderr ---\n";
        provider.enqueueResponse(response("firstAttempt", "assertThat(subject.nope()).isEqualTo(2);"))
                .enqueueResponse(response("repairedAttempt", "assertThat(subject.classify(500)).isEqualTo(2);"));
        build.failsToCompileWithoutDiagnostics(rawLog)
                .compilesSuccessfully(1)
                .scopedTestsPass()
                .coverage(coverageWith(5));

        UnitOutcome outcome = processor().process(context());

        assertThat(outcome.status()).isEqualTo(UnitStatus.DONE);
        assertThat(provider.receivedPrompts()).hasSize(2);
        assertThat(provider.receivedPrompts().get(1)).contains("some plugin blew up");

        Path buildLog = moduleDir.resolve("state").resolve("transcripts")
                .resolve("com.acme.PaymentService#classify(int)@PLAIN_UNIT").resolve("compile-repair-0.build.log");
        assertThat(Files.readString(buildLog)).isEqualTo(rawLog);
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
    void anAssertionRepairThatDoesNotCompileGetsACompileRepairRoundInsteadOfEndingTheUnit() throws IOException {
        // Found against a local model: its assertion repair used matchers it never imported.
        provider.enqueueResponse(response("wrongExpectation", "assertThat(subject.classify(500)).isEqualTo(9);"))
                .enqueueResponse(response("repairedButMissingAnImport", "assertThat(subject.classify(500)).isEqualTo(2);"))
                .enqueueResponse(response("repairedAndCompiling", "assertThat(subject.classify(500)).isEqualTo(2);"));
        build.compilesSuccessfully(1)
                .failsToCompile("cannot find symbol: anyString")
                .compilesSuccessfully(1)
                .scopedTestsFail("expected 9 but was 2")
                .scopedTestsPass()
                .coverage(coverageWith(5));

        UnitOutcome outcome = processor().process(context());

        assertThat(outcome.status()).isEqualTo(UnitStatus.DONE);
        assertThat(outcome.addedTests()).containsExactly("repairedAndCompiling");
        assertThat(provider.receivedPrompts()).hasSize(3);
        assertThat(provider.receivedPrompts().get(2)).contains("cannot find symbol: anyString");
        assertThat(Files.readString(testFile))
                .contains("repairedAndCompiling")
                .doesNotContain("wrongExpectation")
                .doesNotContain("repairedButMissingAnImport");
    }

    @Test
    void everyAiCallOfAUnitGetsItsOwnTranscriptNumberSoNoRepairOverwritesAnother() throws IOException {
        // Phase 4. Generation, an assertion repair and a compile repair used to be numbered 1, 2
        // and 2: the last transcript silently replaced the one before it.
        provider.enqueueResponse(response("wrongExpectation", "assertThat(subject.classify(500)).isEqualTo(9);"))
                .enqueueResponse(response("repairedButMissingAnImport", "assertThat(subject.classify(500)).isEqualTo(2);"))
                .enqueueResponse(response("repairedAndCompiling", "assertThat(subject.classify(500)).isEqualTo(2);"));
        build.compilesSuccessfully(1)
                .failsToCompile("cannot find symbol: anyString")
                .compilesSuccessfully(1)
                .scopedTestsFail("expected 9 but was 2")
                .scopedTestsPass()
                .coverage(coverageWith(5));

        processor().process(context());

        Path unitDir;
        try (var units = Files.list(moduleDir.resolve("state").resolve("transcripts"))) {
            unitDir = units.findFirst().orElseThrow();
        }
        try (var files = Files.list(unitDir)) {
            assertThat(files.map(file -> file.getFileName().toString()).filter(name -> name.endsWith(".prompt.md")))
                    .containsExactlyInAnyOrder("1.prompt.md", "2.prompt.md", "3.prompt.md");
        }
        for (int call = 1; call <= 3; call++) {
            assertThat(Files.readString(unitDir.resolve(call + ".prompt.md")))
                    .isEqualTo(provider.receivedPrompts().get(call - 1));
        }
    }

    @Test
    void anAssertionRepairThatNeverCompilesStillEndsFailedAssertionWithBoundedCallsAndTheFileRestored()
            throws IOException {
        String original = Files.readString(testFile);
        provider.enqueueResponse(response("wrongExpectation", "assertThat(subject.classify(500)).isEqualTo(9);"))
                .enqueueResponse(response("brokenRepair", "assertThat(subject.classify(500)).isEqualTo(2);"))
                .enqueueResponse(response("brokenAgain", "assertThat(subject.classify(500)).isEqualTo(2);"))
                .enqueueResponse(response("brokenOnceMore", "assertThat(subject.classify(500)).isEqualTo(2);"));
        build.compilesSuccessfully(1)
                .failsToCompile("cannot find symbol: a")
                .failsToCompile("cannot find symbol: b")
                .failsToCompile("cannot find symbol: c")
                .scopedTestsFail("expected 9 but was 2");

        UnitOutcome outcome = processor().process(context());

        assertThat(outcome.status()).isEqualTo(UnitStatus.FAILED_ASSERTION);
        // generation + assertion repair + maxRepairAttempts (2) compile repairs, and no more
        assertThat(provider.receivedPrompts()).hasSize(4);
        assertThat(Files.readString(testFile)).isEqualTo(original);
    }

    /**
     * {@code requireCoverageGain} defaults to {@code false} - explicitly enabled here to
     * pin the strict behaviour, which is no longer what a bare {@link #generateConfig()}
     * exercises. See {@link #withRequireCoverageGainOffByDefaultAZeroDeltaCandidateIsKeptAnyway()}
     * for the (now default) opposite.
     */
    @Test
    void zeroCoverageDeltaOnAPlainUnitDiscardsTheTestsAndRevertsTheFileWhenRequireCoverageGainIsOn() throws IOException {
        String original = Files.readString(testFile);
        provider.enqueueResponse(response("coversNothingNew", "assertThat(subject.classify(500)).isEqualTo(2);"));
        build.compilesSuccessfully(1).scopedTestsPass().coverage(coverageWith(2));
        GenerateConfig strict = new GenerateConfig(null, 2, true, null, null);

        UnitOutcome outcome = processor(SpringGenerationSupport.forNewRun(40), strict).process(context());

        assertThat(outcome.status()).isEqualTo(UnitStatus.DISCARDED_NO_VALUE);
        assertThat(Files.readString(testFile)).isEqualTo(original);
    }

    /**
     * The new default: a candidate that compiles and passes is kept even though it moved
     * no coverage - and, just as importantly, {@code jacoco:report} (here,
     * {@code ModuleBuild.measureCoverage}) is never even called to find that out, so a
     * broken JaCoCo setup on the target module cannot block this.
     */
    @Test
    void withRequireCoverageGainOffByDefaultAZeroDeltaCandidateIsKeptAnyway() throws IOException {
        provider.enqueueResponse(response("coversNothingNewButStillCompilesAndPasses",
                "assertThat(subject.classify(500)).isEqualTo(2);"));
        build.compilesSuccessfully(1).scopedTestsPass();

        UnitOutcome outcome = processor().process(context());

        assertThat(outcome.status()).isEqualTo(UnitStatus.DONE);
        assertThat(outcome.addedTests()).containsExactly("coversNothingNewButStillCompilesAndPasses");
        assertThat(build.calls()).noneMatch(call -> call.startsWith("measureCoverage"));
    }

    @Test
    void progressReportsCompileAndTestResultsButNeverFileContent() throws IOException {
        List<String> progress = new java.util.ArrayList<>();
        provider.enqueueResponse(response("classify_aboveThreshold_returnsTwo",
                "assertThat(subject.classify(500)).isEqualTo(2);"));
        build.compilesSuccessfully(1).scopedTestsPass();

        UnitOutcome outcome = processorWithProgress(generateConfig(), progress::add).process(context());

        assertThat(outcome.status()).isEqualTo(UnitStatus.DONE);
        assertThat(progress).anySatisfy(line -> assertThat(line).contains("compile: OK"));
        assertThat(progress).anySatisfy(line -> assertThat(line).contains("tests: PASSED"));
        assertThat(progress).noneMatch(line -> line.contains("classify_aboveThreshold_returnsTwo"));
    }

    @Test
    void progressReportsACompileFailureWithItsErrorCount() throws IOException {
        List<String> progress = new java.util.ArrayList<>();
        provider.enqueueResponse(response("firstAttempt", "assertThat(subject.nope()).isEqualTo(2);"))
                .enqueueResponse(response("repairedAttempt", "assertThat(subject.classify(500)).isEqualTo(2);"));
        build.failsToCompile("cannot find symbol: nope").compilesSuccessfully(1).scopedTestsPass();

        processorWithProgress(generateConfig(), progress::add).process(context());

        assertThat(progress).anySatisfy(line -> assertThat(line).contains("compile: FAILED"));
    }

    @Test
    void progressReportsTheCoverageVerdictOnlyWhenRequireCoverageGainIsOn() throws IOException {
        List<String> progress = new java.util.ArrayList<>();
        provider.enqueueResponse(response("classify_aboveThreshold_returnsTwo",
                "assertThat(subject.classify(500)).isEqualTo(2);"));
        build.compilesSuccessfully(1).scopedTestsPass().coverage(coverageWith(5));
        GenerateConfig strict = new GenerateConfig(null, 2, true, null, null);

        processorWithProgress(strict, progress::add).process(context());

        assertThat(progress).anySatisfy(line -> assertThat(line).contains("coverage:"));
    }

    @Test
    void progressDoesNotReportCoverageWhenRequireCoverageGainIsOff() throws IOException {
        List<String> progress = new java.util.ArrayList<>();
        provider.enqueueResponse(response("coversNothingNewButStillCompilesAndPasses",
                "assertThat(subject.classify(500)).isEqualTo(2);"));
        build.compilesSuccessfully(1).scopedTestsPass();

        processorWithProgress(generateConfig(), progress::add).process(context());

        assertThat(progress).noneMatch(line -> line.contains("coverage:"));
    }

    /**
     * A scoped run that fails without Surefire reporting a single result means the tests
     * never ran - an old Surefire that cannot select {@code Class#method}, "No tests were
     * executed", a plugin failure. The fix-assertion prompt would then show the model a
     * "Failures" section reading "(none)", asking it to fix something it cannot see, at
     * the cost of a full AI round trip per repair attempt.
     */
    @Test
    void aScopedRunThatReportsNoResultAtAllIsNotSentBackToTheModelAsAnAssertionFailure() throws IOException {
        String original = Files.readString(testFile);
        provider.enqueueResponse(response("someTest", "assertThat(subject.classify(500)).isEqualTo(2);"));
        build.compilesSuccessfully(1)
                .scopedRunNeverRan("[ERROR] No tests were executed!");

        UnitOutcome outcome = processor().process(context());

        assertThat(outcome.status()).isEqualTo(UnitStatus.FAILED_ASSERTION);
        assertThat(outcome.error()).contains("no test result at all").contains("No tests were executed");
        // The one that matters: the model was asked once, not once per repair round.
        assertThat(provider.receivedPrompts()).hasSize(1);
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
        provider.enqueueResponse("I could not write those tests, sorry.")
                .enqueueResponse("Still could not, sorry.");

        UnitOutcome outcome = processor().process(context());

        assertThat(outcome.status()).isEqualTo(UnitStatus.DISCARDED_NO_VALUE);
        assertThat(outcome.discarded()).anySatisfy(reason ->
                assertThat(reason).contains("response contract violation"));
        assertThat(Files.readString(testFile)).isEqualTo(original);
    }

    // --- phase 4b (#10): the single corrective re-prompt of §6.2 ------------------------------

    @Test
    void anAnswerThatBreaksTheContractGetsOneCorrectiveRePromptAndTheCorrectedAnswerIsKept() throws IOException {
        // The shape a local model actually produced: imports and tests in one java block,
        // preceded by a whole class declaration - unreadable as a list of test methods.
        String broken = """
                ```java
                class PaymentServiceTest {
                    @Test
                    void classify_aboveThreshold_returnsTwo() {
                        assertThat(subject.classify(500)).isEqualTo(2);
                    }
                }
                ```
                """;
        provider.enqueueResponse(broken)
                .enqueueResponse(response("classify_aboveThreshold_returnsTwo",
                        "assertThat(subject.classify(500)).isEqualTo(2);"));
        build.compilesSuccessfully(1).scopedTestsPass().coverage(coverageWith(5));

        UnitOutcome outcome = processor().process(context());

        assertThat(outcome.status()).isEqualTo(UnitStatus.DONE);
        assertThat(outcome.addedTests()).containsExactly("classify_aboveThreshold_returnsTwo");
        assertThat(provider.receivedPrompts()).hasSize(2);
        String corrective = provider.receivedPrompts().get(1);
        assertThat(corrective).contains("Your previous answer could not be used")
                .contains("single class/interface declaration")
                .contains("class PaymentServiceTest {");
        assertThat(outcome.discarded()).anySatisfy(entry ->
                assertThat(entry).contains("asked the model to correct it"));
    }

    @Test
    void theCorrectionIsSpentOncePerUnitSoALaterBrokenAnswerEndsTheUnitWithoutAnotherOne() throws IOException {
        // Broken, corrected (but does not compile), then a compile repair that is broken again:
        // no second corrective prompt - three calls in all, not four.
        provider.enqueueResponse("no fenced block at all")
                .enqueueResponse(response("firstAttempt", "assertThat(subject.nope()).isEqualTo(2);"))
                .enqueueResponse("still no fenced block");
        build.failsToCompile("cannot find symbol: nope");

        UnitOutcome outcome = processor().process(context());

        assertThat(outcome.status()).isEqualTo(UnitStatus.FAILED_COMPILE);
        assertThat(provider.receivedPrompts()).hasSize(3);
        assertThat(provider.receivedPrompts().get(2)).contains("cannot find symbol: nope")
                .doesNotContain("Your previous answer could not be used");
        assertThat(Files.readString(testFile)).isEqualTo(EXISTING_TEST_CLASS);
    }

    @Test
    void thePreviousAnswerIsShownWithItsPlaceholderLookalikesDefused() throws IOException {
        // Nothing in the model's own text may be expanded by the template renderer.
        provider.enqueueResponse("Here is {{CLASS_SOURCE}} and {{RULES}}, no code block.")
                .enqueueResponse("still nothing");

        processor().process(context());

        String corrective = provider.receivedPrompts().get(1);
        assertThat(corrective).contains("Here is { {CLASS_SOURCE}} and { {RULES}}, no code block.");
    }

    @Test
    void aRunawayPreviousAnswerIsCappedInTheCorrectivePrompt() throws IOException {
        String runaway = "import static org.example.Matchers.status;\n".repeat(2_000);
        provider.enqueueResponse(runaway).enqueueResponse("still nothing");

        processor().process(context());

        String corrective = provider.receivedPrompts().get(1);
        assertThat(corrective).contains("truncated by JTestForge");
        assertThat(corrective.length()).isLessThan(runaway.length());
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

    private DefaultUnitProcessor processorWithProgress(GenerateConfig config, java.util.function.Consumer<String> progress) {
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
                SpringGenerationSupport.forNewRun(40),
                progress);
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
