package com.devmanchego.jtestforge.orchestration;

import com.devmanchego.jtestforge.analysis.TestClassReverter;
import com.devmanchego.jtestforge.model.Phase;
import com.devmanchego.jtestforge.model.RunState;
import com.devmanchego.jtestforge.model.SpringTierState;
import com.devmanchego.jtestforge.model.Tier;
import com.devmanchego.jtestforge.model.UnitStatus;
import com.devmanchego.jtestforge.model.WorkUnit;
import com.devmanchego.jtestforge.model.WorkUnitId;
import com.devmanchego.jtestforge.state.LockFile;
import com.devmanchego.jtestforge.state.StateStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * jtestforge-specification.md §14.1's shutdown hook, simulated directly: a real
 * {@code SIGINT} is not something a unit test can trigger reliably, so
 * {@link InterruptHandler#handleInterrupt()} - the cleanup a real hook would run - is
 * called directly, matching the implementation plan's own "a simulated interrupt" phrasing.
 */
class InterruptHandlerTest {

    private static final Instant NOW = Instant.parse("2026-08-31T10:00:00Z");
    private static final String EXISTING_TEST_CLASS = """
            package com.acme;

            import org.junit.jupiter.api.Test;

            class PaymentServiceTest {

                @Test
                void anExistingTest() {
                    assertEquals(1, 1);
                }

                @Test
                void classify_partiallyMerged() {
                    assertThat(subject.classify(500)).isEqualTo(2);
                }
            }
            """;

    @Test
    void anInProgressUnitsRecordedEditsAreRevertedLeavingAResumableState(@TempDir Path moduleDir) throws IOException {
        Path testFile = moduleDir.resolve("PaymentServiceTest.java");
        Files.writeString(testFile, EXISTING_TEST_CLASS);
        Path stateDir = moduleDir.resolve(".jtestforge");
        StateStore stateStore = store(stateDir);
        LockFile lock = LockFile.acquire(stateDir, Clock.fixed(NOW, ZoneOffset.UTC));

        WorkUnitId id = WorkUnitId.of("com.acme.PaymentService", "classify(int)", Tier.PLAIN_UNIT);
        WorkUnit inProgress = WorkUnit.pending(id, testFile.toString(), "PaymentService.java", "sha256:src")
                .withStatus(UnitStatus.IN_PROGRESS)
                .withAdded(List.of("classify_partiallyMerged"), List.of());
        stateStore.save(RunState.startNew("run-1", NOW, Phase.GENERATE, moduleDir.toString(), "sha256:cfg",
                "claude", SpringTierState.springDisabled(40), List.of(inProgress)));

        new InterruptHandler(stateStore, lock, new TestClassReverter(), moduleDir).handleInterrupt();

        // The working tree is clean: the partially-merged method is gone, everything
        // hand-written survives.
        String finalSource = Files.readString(testFile);
        assertThat(finalSource).doesNotContain("classify_partiallyMerged");
        assertThat(finalSource).contains("anExistingTest");

        // State is still resumable: the unit's own status is untouched by this handler
        // (it stays IN_PROGRESS), which is exactly what makes resume reconciliation
        // recognise it as "a crash happened here" (§8.1) on the next run.
        RunState resumable = stateStore.load().orElseThrow();
        assertThat(resumable.unit(id).orElseThrow().status()).isEqualTo(UnitStatus.IN_PROGRESS);

        // The lock is released - a resumed run is not blocked by the dead one.
        assertThat(Files.exists(lock.lockPath())).isFalse();
    }

    @Test
    void aUnitWithNothingMergedYetIsLeftAloneOtherThanTheLockRelease(@TempDir Path moduleDir) throws IOException {
        Path testFile = moduleDir.resolve("PaymentServiceTest.java");
        Files.writeString(testFile, EXISTING_TEST_CLASS);
        String originalSource = Files.readString(testFile);
        Path stateDir = moduleDir.resolve(".jtestforge");
        StateStore stateStore = store(stateDir);
        LockFile lock = LockFile.acquire(stateDir, Clock.fixed(NOW, ZoneOffset.UTC));

        WorkUnitId id = WorkUnitId.of("com.acme.PaymentService", "classify(int)", Tier.PLAIN_UNIT);
        // Marked IN_PROGRESS (write-ahead, §8.2.1) but the crash happened before any merge.
        WorkUnit inProgress = WorkUnit.pending(id, testFile.toString(), "PaymentService.java", "sha256:src")
                .withStatus(UnitStatus.IN_PROGRESS);
        stateStore.save(RunState.startNew("run-1", NOW, Phase.GENERATE, moduleDir.toString(), "sha256:cfg",
                "claude", SpringTierState.springDisabled(40), List.of(inProgress)));

        new InterruptHandler(stateStore, lock, new TestClassReverter(), moduleDir).handleInterrupt();

        assertThat(Files.readString(testFile)).isEqualTo(originalSource);
        assertThat(Files.exists(lock.lockPath())).isFalse();
    }

    @Test
    void unitsNotInProgressAreNeverTouched(@TempDir Path moduleDir) throws IOException {
        Path testFile = moduleDir.resolve("PaymentServiceTest.java");
        Files.writeString(testFile, EXISTING_TEST_CLASS);
        String originalSource = Files.readString(testFile);
        Path stateDir = moduleDir.resolve(".jtestforge");
        StateStore stateStore = store(stateDir);
        LockFile lock = LockFile.acquire(stateDir, Clock.fixed(NOW, ZoneOffset.UTC));

        WorkUnitId doneId = WorkUnitId.of("com.acme.PaymentService", "settle(String)", Tier.PLAIN_UNIT);
        WorkUnit done = WorkUnit.pending(doneId, testFile.toString(), "PaymentService.java", "sha256:src")
                .withStatus(UnitStatus.DONE).withAdded(List.of("anExistingTest"), List.of());
        stateStore.save(RunState.startNew("run-1", NOW, Phase.GENERATE, moduleDir.toString(), "sha256:cfg",
                "claude", SpringTierState.springDisabled(40), List.of(done)));

        new InterruptHandler(stateStore, lock, new TestClassReverter(), moduleDir).handleInterrupt();

        assertThat(Files.readString(testFile)).isEqualTo(originalSource);
    }

    @Test
    void withNoStateFileAtAllTheHandlerStillReleasesTheLockWithoutThrowing(@TempDir Path moduleDir) {
        Path stateDir = moduleDir.resolve(".jtestforge");
        StateStore stateStore = store(stateDir);
        LockFile lock = LockFile.acquire(stateDir, Clock.fixed(NOW, ZoneOffset.UTC));

        new InterruptHandler(stateStore, lock, new TestClassReverter(), moduleDir).handleInterrupt();

        assertThat(Files.exists(lock.lockPath())).isFalse();
    }

    @Test
    void installAndUninstallRegisterAndRemoveARealShutdownHookWithoutRunningIt(@TempDir Path moduleDir) {
        Path stateDir = moduleDir.resolve(".jtestforge");
        StateStore stateStore = store(stateDir);
        LockFile lock = LockFile.acquire(stateDir, Clock.fixed(NOW, ZoneOffset.UTC));
        InterruptHandler handler = new InterruptHandler(stateStore, lock, new TestClassReverter(), moduleDir);

        Thread hook = handler.install();
        handler.uninstall(hook);

        // The lock is untouched: removing the hook before it ever ran must not trigger
        // its cleanup - only a real, uncaught shutdown should.
        assertThat(Files.exists(lock.lockPath())).isTrue();
        lock.release();
    }

    private StateStore store(Path stateDir) {
        return new StateStore(stateDir, Clock.fixed(NOW, ZoneOffset.UTC));
    }
}
