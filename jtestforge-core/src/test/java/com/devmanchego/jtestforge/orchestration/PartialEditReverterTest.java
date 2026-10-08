package com.devmanchego.jtestforge.orchestration;

import com.devmanchego.jtestforge.analysis.TestClassReverter;
import com.devmanchego.jtestforge.analysis.TestClassScanner;
import com.devmanchego.jtestforge.model.Phase;
import com.devmanchego.jtestforge.model.RunState;
import com.devmanchego.jtestforge.model.SpringTierState;
import com.devmanchego.jtestforge.model.Tier;
import com.devmanchego.jtestforge.model.UnitStatus;
import com.devmanchego.jtestforge.model.WorkUnit;
import com.devmanchego.jtestforge.model.WorkUnitId;
import com.devmanchego.jtestforge.state.ReconciliationResult;
import com.devmanchego.jtestforge.state.ResumeReconciler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Resume after a kill mid-unit: the journal recorded what the unit had merged, and the next
 * run has to take exactly that out of the test file.
 */
class PartialEditReverterTest {

    private static final String RELATIVE_TEST_FILE = "src/test/java/com/acme/PaymentServiceTest.java";

    @TempDir
    Path moduleDir;

    @Test
    void resumeRevertsWhatTheInterruptedUnitJournaledWhenGivenTheStateAsLoaded() throws IOException {
        Path testFile = writeHalfMergedTestClass();
        RunState loaded = stateWithInterruptedUnit();
        ReconciliationResult reconciliation = new ResumeReconciler(new TestClassScanner()::testMethodNames)
                .reconcile(loaded, moduleDir, "sha256:cfg");

        new PartialEditReverter(new TestClassReverter(), moduleDir)
                .revert(loaded, reconciliation.unitsNeedingRevert());

        String reverted = Files.readString(testFile);
        assertThat(reverted).doesNotContain("halfMerged").doesNotContain("import java.time.Clock;");
        assertThat(reverted).contains("void anExistingTest()");
    }

    @Test
    void theReconciledStateNoLongerKnowsWhatToRevertWhichIsWhyItMustNotBeTheOnePassed() throws IOException {
        // Pins the trap resume used to fall into: resetToPending() clears addedTests, so
        // reverting from the reconciled state silently did nothing.
        Path testFile = writeHalfMergedTestClass();
        RunState loaded = stateWithInterruptedUnit();
        ReconciliationResult reconciliation = new ResumeReconciler(new TestClassScanner()::testMethodNames)
                .reconcile(loaded, moduleDir, "sha256:cfg");
        WorkUnitId unitId = reconciliation.unitsNeedingRevert().get(0);

        assertThat(reconciliation.state().unit(unitId).orElseThrow().addedTests()).isEmpty();
        new PartialEditReverter(new TestClassReverter(), moduleDir)
                .revert(reconciliation.state(), reconciliation.unitsNeedingRevert());
        assertThat(Files.readString(testFile)).contains("halfMerged");
    }

    @Test
    void aJournaledMethodThatNeverReachedTheFileIsANoOp() throws IOException {
        // The kill landed between journaling and writing: the journal names a method the file
        // does not have, and the file must come out byte for byte unchanged.
        Path testFile = moduleDir.resolve(RELATIVE_TEST_FILE);
        Files.createDirectories(testFile.getParent());
        String untouched = """
                package com.acme;

                import org.junit.jupiter.api.Test;

                class PaymentServiceTest {

                    @Test
                    void anExistingTest() {
                        assertEquals(1, 1);
                    }
                }
                """;
        Files.writeString(testFile, untouched);

        new PartialEditReverter(new TestClassReverter(), moduleDir)
                .revert(stateWithInterruptedUnit(), List.of(unitId()));

        assertThat(Files.readString(testFile)).isEqualTo(untouched);
    }

    // --- a test class the interrupted unit created ---------------------------------------------

    @Test
    void aSkeletonTheUnitCreatedIsRemovedWhenItWasKilledBeforeMergingAnything() throws IOException {
        // The common kill: during the AI call, right after the skeleton was written.
        Path testFile = writeTestFile("""
                package com.acme;

                import org.junit.jupiter.api.Test;

                class PaymentServiceTest {
                }
                """);
        WorkUnit unit = interruptedUnit().withCreatedTestFile(true);

        new PartialEditReverter(new TestClassReverter(), moduleDir).revert(unit);

        assertThat(testFile).doesNotExist();
    }

    @Test
    void aSkeletonTheUnitCreatedIsRemovedOnceItsHalfMergedTestIsTakenOut() throws IOException {
        Path testFile = writeTestFile("""
                package com.acme;

                import java.time.Clock;
                import org.junit.jupiter.api.Test;

                class PaymentServiceTest {

                    @Test
                    void halfMerged() {
                        Clock clock = Clock.systemUTC();
                    }
                }
                """);
        WorkUnit unit = interruptedUnit().withAdded(List.of("halfMerged"), List.of("java.time.Clock"))
                .withCreatedTestFile(true);

        new PartialEditReverter(new TestClassReverter(), moduleDir).revert(unit);

        assertThat(testFile).doesNotExist();
    }

    @Test
    void aCreatedClassThatHoldsAnyOtherTestIsKept() throws IOException {
        Path testFile = writeHalfMergedTestClass();
        WorkUnit unit = interruptedUnit().withAdded(List.of("halfMerged"), List.of("java.time.Clock"))
                .withCreatedTestFile(true);

        new PartialEditReverter(new TestClassReverter(), moduleDir).revert(unit);

        assertThat(Files.readString(testFile)).contains("void anExistingTest()").doesNotContain("halfMerged");
    }

    @Test
    void anEmptyClassTheUnitDidNotCreateIsNeverDeleted() throws IOException {
        Path testFile = writeTestFile("""
                package com.acme;

                class PaymentServiceTest {
                }
                """);

        new PartialEditReverter(new TestClassReverter(), moduleDir).revert(interruptedUnit());

        assertThat(testFile).exists();
    }

    // --- revertUnfinished: shutdown hook and --restart ------------------------------------------

    @Test
    void revertUnfinishedTakesOutInterruptedAndLeftoverUnitsButNeverADoneUnitsTests() throws IOException {
        Path testFile = writeTestFile("""
                package com.acme;

                import org.junit.jupiter.api.Test;

                class PaymentServiceTest {

                    @Test
                    void kept() {
                    }

                    @Test
                    void interrupted() {
                    }

                    @Test
                    void leftOverByAFailedRollback() {
                    }
                }
                """);
        WorkUnit done = unit("a()").withStatus(UnitStatus.DONE).withAdded(List.of("kept"), List.of());
        WorkUnit interrupted = unit("b()").withStatus(UnitStatus.IN_PROGRESS)
                .withAdded(List.of("interrupted"), List.of());
        WorkUnit leftover = unit("c()").withStatus(UnitStatus.PROVIDER_ERROR)
                .withAdded(List.of("leftOverByAFailedRollback"), List.of());

        new PartialEditReverter(new TestClassReverter(), moduleDir).revertUnfinished(stateWith(done, interrupted, leftover));

        assertThat(Files.readString(testFile)).contains("void kept()")
                .doesNotContain("interrupted").doesNotContain("leftOverByAFailedRollback");
    }

    private Path writeTestFile(String content) throws IOException {
        Path testFile = moduleDir.resolve(RELATIVE_TEST_FILE);
        Files.createDirectories(testFile.getParent());
        Files.writeString(testFile, content);
        return testFile;
    }

    private WorkUnit interruptedUnit() {
        return unit("classify(int)").withStatus(UnitStatus.IN_PROGRESS);
    }

    private WorkUnit unit(String method) {
        return WorkUnit.pending(WorkUnitId.of("com.acme.PaymentService", method, Tier.PLAIN_UNIT), RELATIVE_TEST_FILE,
                "src/main/java/com/acme/PaymentService.java", "sha256:src");
    }

    private RunState stateWith(WorkUnit... units) {
        return RunState.startNew("run-1", Instant.parse("2026-10-06T10:00:00Z"), Phase.GENERATE,
                moduleDir.toString(), "sha256:cfg", "claude", SpringTierState.springDisabled(40), List.of(units));
    }

    private Path writeHalfMergedTestClass() throws IOException {
        Path testFile = moduleDir.resolve(RELATIVE_TEST_FILE);
        Files.createDirectories(testFile.getParent());
        Files.writeString(testFile, """
                package com.acme;

                import java.time.Clock;
                import org.junit.jupiter.api.Test;

                class PaymentServiceTest {

                    @Test
                    void anExistingTest() {
                        assertEquals(1, 1);
                    }

                    @Test
                    void halfMerged() {
                        Clock clock = Clock.systemUTC();
                        assertEquals(1, 1);
                    }
                }
                """);
        return testFile;
    }

    private RunState stateWithInterruptedUnit() {
        WorkUnit interrupted = WorkUnit.pending(unitId(), RELATIVE_TEST_FILE,
                        "src/main/java/com/acme/PaymentService.java", "sha256:src")
                .withStatus(UnitStatus.IN_PROGRESS)
                .withAdded(List.of("halfMerged"), List.of("java.time.Clock"));
        return RunState.startNew("run-1", Instant.parse("2026-10-06T10:00:00Z"), Phase.GENERATE,
                moduleDir.toString(), "sha256:cfg", "claude", SpringTierState.springDisabled(40),
                List.of(interrupted));
    }

    private WorkUnitId unitId() {
        return WorkUnitId.of("com.acme.PaymentService", "classify(int)", Tier.PLAIN_UNIT);
    }
}
