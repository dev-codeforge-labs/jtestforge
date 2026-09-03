package com.devmanchego.jtestforge.state;

import com.devmanchego.jtestforge.model.Baseline;
import com.devmanchego.jtestforge.model.Phase;
import com.devmanchego.jtestforge.model.RunState;
import com.devmanchego.jtestforge.model.SpringTierState;
import com.devmanchego.jtestforge.model.Tier;
import com.devmanchego.jtestforge.model.UnitStatus;
import com.devmanchego.jtestforge.model.WorkUnit;
import com.devmanchego.jtestforge.model.WorkUnitId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StateStoreTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-08-27T10:12:44Z");
    private final Clock clock = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);

    @Test
    void loadingReturnsEmptyWhenNoStateFileExistsYet(@TempDir Path stateDir) {
        StateStore store = new StateStore(stateDir, clock);

        assertThat(store.load()).isEmpty();
    }

    @Test
    void aSavedStateRoundTripsThroughJsonWithEveryFieldPreserved(@TempDir Path stateDir) {
        StateStore store = new StateStore(stateDir, clock);
        RunState original = runStateWithOneDoneUnit();

        store.save(original);
        RunState reloaded = store.load().orElseThrow();

        assertThat(reloaded.runId()).isEqualTo(original.runId());
        assertThat(reloaded.phase()).isEqualTo(Phase.GENERATE);
        assertThat(reloaded.configHash()).isEqualTo(original.configHash());
        assertThat(reloaded.baseline().lineCoverage()).isEqualTo(0.412);
        assertThat(reloaded.baseline().mutationScore()).isNull();
        assertThat(reloaded.springTiers().available()).containsExactlyInAnyOrder(Tier.PLAIN_UNIT, Tier.WEB_SLICE);
        assertThat(reloaded.springTiers().unavailable())
                .containsEntry(Tier.DATA_SLICE, "no embedded database on the test classpath");
        assertThat(reloaded.units()).hasSize(1);

        WorkUnit unit = reloaded.units().get(0);
        assertThat(unit.id()).isEqualTo(original.units().get(0).id());
        assertThat(unit.status()).isEqualTo(UnitStatus.DONE);
        assertThat(unit.addedTests()).containsExactly("applyFee_roundsHalfUp", "applyFee_rejectsNegative");
        assertThat(unit.sourceHash()).isEqualTo("sha256:1b8c");
        assertThat(unit.linesCoveredDelta()).isEqualTo(7);
    }

    @Test
    void aTruncatedStateFileIsDetectedRatherThanTreatedAsAFreshStart(@TempDir Path stateDir) throws IOException {
        // The exact failure §8.2.2 exists to prevent: a kill during a write. Reading this
        // as "no state, start over" would silently discard a completed run's bookkeeping.
        StateStore store = new StateStore(stateDir, clock);
        store.save(runStateWithOneDoneUnit());

        Path stateFile = stateDir.resolve(StateStore.STATE_FILE_NAME);
        String full = Files.readString(stateFile);
        Files.writeString(stateFile, full.substring(0, full.length() / 2));

        assertThatThrownBy(store::load)
                .isInstanceOf(StateCorruptException.class)
                .hasMessageContaining("state.json");
    }

    @Test
    void anUnsupportedSchemaVersionIsRefused(@TempDir Path stateDir) throws IOException {
        Path stateFile = stateDir.resolve(StateStore.STATE_FILE_NAME);
        Files.writeString(stateFile, """
                {"schemaVersion": 99, "runId": "r1", "phase": "GENERATE", "units": []}
                """);
        StateStore store = new StateStore(stateDir, clock);

        assertThatThrownBy(store::load)
                .isInstanceOf(StateCorruptException.class)
                .hasMessageContaining("99");
    }

    @Test
    void savingStampsUpdatedAtFromTheInjectedClock(@TempDir Path stateDir) {
        StateStore store = new StateStore(stateDir, clock);
        RunState original = runStateWithOneDoneUnit()
                .withUpdatedAt(Instant.parse("2020-01-01T00:00:00Z"));

        RunState saved = store.save(original);

        assertThat(saved.updatedAt()).isEqualTo(FIXED_NOW);
        assertThat(store.load().orElseThrow().updatedAt()).isEqualTo(FIXED_NOW);
    }

    @Test
    void markInProgressPersistsBeforeTheCallerDoesAnythingElse(@TempDir Path stateDir) {
        // Write-ahead (§8.2.1): the marker must be on disk when markInProgress returns,
        // not batched until some later save, or a crash mid-unit is indistinguishable
        // from a unit that was never started.
        StateStore store = new StateStore(stateDir, clock);
        RunState state = runStateWithOneDoneUnit()
                .withUnit(doneUnit().resetToPending());
        store.save(state);
        WorkUnitId id = state.units().get(0).id();

        store.markInProgress(state, id);

        RunState onDisk = store.load().orElseThrow();
        assertThat(onDisk.unit(id).orElseThrow().status()).isEqualTo(UnitStatus.IN_PROGRESS);
    }

    @Test
    void markInProgressIncrementsTheAttemptCount(@TempDir Path stateDir) {
        StateStore store = new StateStore(stateDir, clock);
        RunState state = runStateWithOneDoneUnit().withUnit(doneUnit().resetToPending());
        WorkUnitId id = state.units().get(0).id();

        RunState afterFirst = store.markInProgress(state, id);
        RunState afterSecond = store.markInProgress(afterFirst, id);

        assertThat(afterFirst.unit(id).orElseThrow().attempts()).isEqualTo(1);
        assertThat(afterSecond.unit(id).orElseThrow().attempts()).isEqualTo(2);
    }

    @Test
    void twoUnitsForTheSameMethodAtDifferentTiersCoexistWithoutColliding(@TempDir Path stateDir) {
        // §7.4: a @RestController method legitimately holds a PLAIN_UNIT unit for its
        // branching and a WEB_SLICE unit for its mapping contract. If the tier were not
        // part of the identity, one would overwrite the other and silently never run.
        WorkUnitId plainId = WorkUnitId.of("com.acme.FooController", "get(Long)", Tier.PLAIN_UNIT);
        WorkUnitId sliceId = WorkUnitId.of("com.acme.FooController", "get(Long)", Tier.WEB_SLICE);
        RunState state = RunState.startNew("run-1", FIXED_NOW, Phase.GENERATE, "C:/app",
                "sha256:cfg", "claude", SpringTierState.springDisabled(40),
                List.of(
                        WorkUnit.pending(plainId, "src/test/java/com/acme/FooControllerTest.java",
                                "src/main/java/com/acme/FooController.java", "sha256:src"),
                        WorkUnit.pending(sliceId, "src/test/java/com/acme/FooControllerWebTest.java",
                                "src/main/java/com/acme/FooController.java", "sha256:src")));
        StateStore store = new StateStore(stateDir, clock);

        RunState afterPlainDone = store.updateUnit(state,
                state.unit(plainId).orElseThrow().withStatus(UnitStatus.DONE));

        RunState onDisk = store.load().orElseThrow();
        assertThat(onDisk.units()).hasSize(2);
        assertThat(onDisk.unit(plainId).orElseThrow().status()).isEqualTo(UnitStatus.DONE);
        assertThat(onDisk.unit(sliceId).orElseThrow().status()).isEqualTo(UnitStatus.PENDING);
        assertThat(afterPlainDone.unit(sliceId).orElseThrow().status()).isEqualTo(UnitStatus.PENDING);
    }

    @Test
    void updatingAUnitThatIsNotPartOfTheRunIsRejectedRatherThanAppended(@TempDir Path stateDir) {
        StateStore store = new StateStore(stateDir, clock);
        RunState state = runStateWithOneDoneUnit();
        WorkUnit strayUnit = WorkUnit.pending(
                WorkUnitId.of("com.acme.NotDiscovered", "x()", Tier.PLAIN_UNIT),
                "src/test/java/com/acme/NotDiscoveredTest.java",
                "src/main/java/com/acme/NotDiscovered.java", "sha256:x");

        assertThatThrownBy(() -> store.updateUnit(state, strayUnit))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theWrittenJsonMatchesTheShapeDocumentedInTheSpecification(@TempDir Path stateDir) throws IOException {
        // state.json is also the run report's source (§15) and is meant to be readable by
        // hand, so its on-disk shape is part of the contract, not an encoding detail.
        StateStore store = new StateStore(stateDir, clock);
        WorkUnitId pendingId = WorkUnitId.of("com.acme.LedgerPoster", "post(Entry)", Tier.PLAIN_UNIT);
        RunState state = runStateWithOneDoneUnit().withUnits(List.of(
                doneUnit(),
                WorkUnit.pending(pendingId, "src/test/java/com/acme/LedgerPosterTest.java",
                        "src/main/java/com/acme/LedgerPoster.java", "sha256:aa")));

        store.save(state);
        String json = Files.readString(stateDir.resolve(StateStore.STATE_FILE_NAME));

        // Ids are single strings, exactly as §8.1 writes them - not nested objects.
        assertThat(json).contains(
                "\"com.acme.PaymentService#applyFee(BigDecimal,Currency)@PLAIN_UNIT\"");
        // Instants are ISO-8601, not epoch millis.
        assertThat(json).contains("\"startedAt\" : \"2026-08-27T10:12:44Z\"");
        // Tier map keys serialise by name.
        assertThat(json).contains("\"DATA_SLICE\"");
        // A never-attempted unit stays compact: no empty arrays or zero counters.
        assertThat(json).doesNotContain("\"linesCoveredDelta\" : 0");
        assertThat(json).doesNotContain("\"discardedTests\" : [ ]");
    }

    @Test
    void everySaveLeavesNoTemporaryFileBehind(@TempDir Path stateDir) throws IOException {
        StateStore store = new StateStore(stateDir, clock);

        store.save(runStateWithOneDoneUnit());
        store.save(runStateWithOneDoneUnit());

        try (var files = Files.list(stateDir)) {
            assertThat(files.map(p -> p.getFileName().toString()))
                    .containsExactly(StateStore.STATE_FILE_NAME);
        }
    }

    // --- fixtures -----------------------------------------------------------------

    private RunState runStateWithOneDoneUnit() {
        return new RunState(
                RunState.CURRENT_SCHEMA_VERSION, "20260827T101244Z-3f9c", FIXED_NOW, FIXED_NOW,
                Phase.GENERATE, "C:/work/ws/myapp/core", "sha256:9f2a", "claude",
                new Baseline(0.412, 0.301, null, 23),
                new SpringTierState(
                        Set.of(Tier.PLAIN_UNIT, Tier.WEB_SLICE),
                        Map.of(Tier.DATA_SLICE, "no embedded database on the test classpath"),
                        6, 40),
                List.of(doneUnit()));
    }

    private WorkUnit doneUnit() {
        return new WorkUnit(
                WorkUnitId.of("com.acme.PaymentService", "applyFee(BigDecimal,Currency)", Tier.PLAIN_UNIT),
                "src/test/java/com/acme/PaymentServiceTest.java",
                "src/main/java/com/acme/PaymentService.java",
                "sha256:1b8c", "sha256:77de", UnitStatus.DONE, 1,
                List.of("applyFee_roundsHalfUp", "applyFee_rejectsNegative"),
                List.of(), List.of(), List.of(), List.of(), 7, 2, 41230L, null, null);
    }
}
