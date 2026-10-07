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
