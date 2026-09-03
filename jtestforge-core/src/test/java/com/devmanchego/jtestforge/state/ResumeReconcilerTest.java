package com.devmanchego.jtestforge.state;

import com.devmanchego.jtestforge.model.Baseline;
import com.devmanchego.jtestforge.model.Phase;
import com.devmanchego.jtestforge.model.RunState;
import com.devmanchego.jtestforge.model.SpringTierState;
import com.devmanchego.jtestforge.model.Tier;
import com.devmanchego.jtestforge.model.UnitStatus;
import com.devmanchego.jtestforge.model.WorkUnit;
import com.devmanchego.jtestforge.model.WorkUnitId;
import com.devmanchego.jtestforge.util.FileHasher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ResumeReconcilerTest {

    private static final Instant NOW = Instant.parse("2026-08-27T10:12:44Z");
    private static final String CONFIG_HASH = "sha256:cfg";
    private static final String TEST_FILE = "src/test/java/com/acme/PaymentServiceTest.java";
    private static final String SOURCE_FILE = "src/main/java/com/acme/PaymentService.java";

    private final WorkUnitId unitId =
            WorkUnitId.of("com.acme.PaymentService", "applyFee(BigDecimal)", Tier.PLAIN_UNIT);

    @Test
    void aDoneUnitWhoseTestsAndSourceAreUnchangedStaysDone(@TempDir Path module) throws IOException {
        String sourceHash = writeSource(module, "public class PaymentService { }");
        writeTestFile(module);
        RunState state = stateWith(doneUnit(sourceHash));
        ResumeReconciler reconciler = new ResumeReconciler(
                inspectorReturning(Set.of("applyFee_roundsHalfUp", "applyFee_rejectsNegative")));

        ReconciliationResult result = reconciler.reconcile(state, module, CONFIG_HASH);

        assertThat(result.state().unit(unitId).orElseThrow().status()).isEqualTo(UnitStatus.DONE);
        assertThat(result.resets()).isEmpty();
        assertThat(result.changedAnything()).isFalse();
    }

    @Test
    void aDoneUnitWhoseTestMethodWasDeletedByHandIsResetToPending(@TempDir Path module) throws IOException {
        String sourceHash = writeSource(module, "public class PaymentService { }");
        writeTestFile(module);
        RunState state = stateWith(doneUnit(sourceHash));
        // Only one of the two recorded tests is still in the file.
        ResumeReconciler reconciler = new ResumeReconciler(
                inspectorReturning(Set.of("applyFee_roundsHalfUp")));

        ReconciliationResult result = reconciler.reconcile(state, module, CONFIG_HASH);

        WorkUnit reconciled = result.state().unit(unitId).orElseThrow();
        assertThat(reconciled.status()).isEqualTo(UnitStatus.PENDING);
        assertThat(reconciled.addedTests()).isEmpty();
        assertThat(reconciled.attempts()).isZero();
        assertThat(result.resets()).hasSize(1);
        assertThat(result.resets().get(0).reason()).contains("applyFee_rejectsNegative");
    }

    @Test
    void aDoneUnitWhoseProductionClassChangedIsResetToPending(@TempDir Path module) throws IOException {
        writeSource(module, "public class PaymentService { }");
        writeTestFile(module);
        // The unit records the hash of a *different* version of the source file.
        RunState state = stateWith(doneUnit("sha256:hash-of-an-older-version"));
        ResumeReconciler reconciler = new ResumeReconciler(
                inspectorReturning(Set.of("applyFee_roundsHalfUp", "applyFee_rejectsNegative")));

        ReconciliationResult result = reconciler.reconcile(state, module, CONFIG_HASH);

        assertThat(result.state().unit(unitId).orElseThrow().status()).isEqualTo(UnitStatus.PENDING);
        assertThat(result.resets().get(0).reason()).containsIgnoringCase("changed");
    }

    @Test
    void aDoneUnitWhoseTestFileVanishedEntirelyIsResetToPending(@TempDir Path module) throws IOException {
        String sourceHash = writeSource(module, "public class PaymentService { }");
        // No test file written at all - e.g. the branch was reverted from version control.
        RunState state = stateWith(doneUnit(sourceHash));
        ResumeReconciler reconciler = new ResumeReconciler(inspectorReturning(Set.of()));

        ReconciliationResult result = reconciler.reconcile(state, module, CONFIG_HASH);

        assertThat(result.state().unit(unitId).orElseThrow().status()).isEqualTo(UnitStatus.PENDING);
        assertThat(result.resets()).hasSize(1);
    }

    @Test
    void anInProgressUnitIsResetAndSurfacedAsNeedingItsPartialEditsReverted(@TempDir Path module)
            throws IOException {
        String sourceHash = writeSource(module, "public class PaymentService { }");
        writeTestFile(module);
        WorkUnit crashed = doneUnit(sourceHash).withStatus(UnitStatus.IN_PROGRESS);
        RunState state = stateWith(crashed);
        ResumeReconciler reconciler = new ResumeReconciler(inspectorReturning(Set.of()));

        ReconciliationResult result = reconciler.reconcile(state, module, CONFIG_HASH);

        assertThat(result.state().unit(unitId).orElseThrow().status()).isEqualTo(UnitStatus.PENDING);
        assertThat(result.unitsNeedingRevert()).containsExactly(unitId);
    }

    @Test
    void aConfigChangeBetweenRunsInvalidatesTheBaseline(@TempDir Path module) throws IOException {
        String sourceHash = writeSource(module, "public class PaymentService { }");
        writeTestFile(module);
        RunState state = stateWith(doneUnit(sourceHash));
        ResumeReconciler reconciler = new ResumeReconciler(
                inspectorReturning(Set.of("applyFee_roundsHalfUp", "applyFee_rejectsNegative")));

        ReconciliationResult result = reconciler.reconcile(state, module, "sha256:a-different-config");

        assertThat(result.baselineInvalidated()).isTrue();
        assertThat(result.state().baseline()).isNull();
        assertThat(result.state().configHash()).isEqualTo("sha256:a-different-config");
        // Completed work survives a config change: those tests exist and still pass. Only
        // the measurements they were judged against are stale.
        assertThat(result.state().unit(unitId).orElseThrow().status()).isEqualTo(UnitStatus.DONE);
    }

    @Test
    void terminalNonSuccessStatusesAreLeftAloneByReconciliation(@TempDir Path module) throws IOException {
        String sourceHash = writeSource(module, "public class PaymentService { }");
        RunState state = stateWith(doneUnit(sourceHash).withStatus(UnitStatus.FAILED_COMPILE));
        ResumeReconciler reconciler = new ResumeReconciler(inspectorReturning(Set.of()));

        ReconciliationResult result = reconciler.reconcile(state, module, CONFIG_HASH);

        // Whether these are retried is the --retry-failed decision (§8.1), made by the
        // engine when it selects work - not something reconciliation should pre-empt.
        assertThat(result.state().unit(unitId).orElseThrow().status()).isEqualTo(UnitStatus.FAILED_COMPILE);
        assertThat(result.resets()).isEmpty();
    }

    @Test
    void reconciliationInspectsEveryDoneUnitIndependently(@TempDir Path module) throws IOException {
        String sourceHash = writeSource(module, "public class PaymentService { }");
        writeTestFile(module);
        WorkUnitId secondId =
                WorkUnitId.of("com.acme.PaymentService", "settle(Order)", Tier.PLAIN_UNIT);
        WorkUnit intact = doneUnit(sourceHash);
        WorkUnit stale = new WorkUnit(secondId, TEST_FILE, SOURCE_FILE, sourceHash, "sha256:77de",
                UnitStatus.DONE, 1, List.of("settle_rollsBackOnFailure"),
                List.of(), List.of(), List.of(), List.of(), 3, 1, 100L, null, null);
        RunState state = stateWith(intact).withUnits(List.of(intact, stale));
        // Only the first unit's tests survive in the file.
        ResumeReconciler reconciler = new ResumeReconciler(
                inspectorReturning(Set.of("applyFee_roundsHalfUp", "applyFee_rejectsNegative")));

        ReconciliationResult result = reconciler.reconcile(state, module, CONFIG_HASH);

        assertThat(result.state().unit(unitId).orElseThrow().status()).isEqualTo(UnitStatus.DONE);
        assertThat(result.state().unit(secondId).orElseThrow().status()).isEqualTo(UnitStatus.PENDING);
    }

    // --- fixtures -----------------------------------------------------------------

    private TestFileInspector inspectorReturning(Set<String> methodNames) {
        return testFile -> methodNames;
    }

    private String writeSource(Path module, String content) throws IOException {
        Path source = module.resolve(SOURCE_FILE);
        Files.createDirectories(source.getParent());
        Files.writeString(source, content);
        return FileHasher.hash(source).orElseThrow();
    }

    private void writeTestFile(Path module) throws IOException {
        Path testFile = module.resolve(TEST_FILE);
        Files.createDirectories(testFile.getParent());
        Files.writeString(testFile, "class PaymentServiceTest { }");
    }

    private WorkUnit doneUnit(String sourceHash) {
        return new WorkUnit(unitId, TEST_FILE, SOURCE_FILE, sourceHash, "sha256:77de",
                UnitStatus.DONE, 1,
                List.of("applyFee_roundsHalfUp", "applyFee_rejectsNegative"),
                List.of(), List.of(), List.of(), List.of(), 7, 2, 41230L, null, null);
    }

    private RunState stateWith(WorkUnit unit) {
        return new RunState(RunState.CURRENT_SCHEMA_VERSION, "run-1", NOW, NOW, Phase.GENERATE,
                "C:/app", CONFIG_HASH, "claude", new Baseline(0.4, 0.3, null, 5),
                new SpringTierState(Set.of(Tier.PLAIN_UNIT), Map.of(), 0, 40),
                List.of(unit));
    }
}
