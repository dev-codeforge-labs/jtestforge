package com.devmanchego.jtestforge.orchestration;

import com.devmanchego.jtestforge.model.Tier;
import com.devmanchego.jtestforge.model.UnitStatus;
import com.devmanchego.jtestforge.model.WorkUnit;
import com.devmanchego.jtestforge.model.WorkUnitId;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The mapping the run report (§15) ultimately reads from: every field an outcome carries
 * must actually land on the persisted {@link WorkUnit}, not just the ones the engines'
 * own tests happen to assert on. {@code gapsClosed}, {@code mutantsKilled} and the
 * coverage deltas were computed correctly by both passes long before anything wrote them
 * onto the unit itself - this pins that they now do.
 */
class WorkUnitOutcomesTest {

    private final WorkUnitId id = WorkUnitId.of("com.acme.PaymentService", "applyFee(BigDecimal)", Tier.PLAIN_UNIT);

    @Test
    void everyFieldOfAKeptOutcomeLandsOnTheUnit() {
        WorkUnit unit = WorkUnit.pending(id, "PaymentServiceTest.java", "PaymentService.java", "sha256:src");
        UnitOutcome outcome = UnitOutcome.kept(
                List.of("applyFee_roundsHalfUp"), List.of("java.math.BigDecimal"),
                List.of("NO_ASSERTION rejected other: no assertion"), List.of("gap-1"), List.of("mutant-1"),
                7, 2);

        WorkUnit updated = WorkUnitOutcomes.apply(unit, outcome, 41230L);

        assertThat(updated.status()).isEqualTo(UnitStatus.DONE);
        assertThat(updated.addedTests()).containsExactly("applyFee_roundsHalfUp");
        assertThat(updated.addedImports()).containsExactly("java.math.BigDecimal");
        assertThat(updated.discardedTests()).containsExactly("NO_ASSERTION rejected other: no assertion");
        assertThat(updated.semanticGapsClosed()).containsExactly("gap-1");
        assertThat(updated.mutantsKilled()).containsExactly("mutant-1");
        assertThat(updated.linesCoveredDelta()).isEqualTo(7);
        assertThat(updated.branchesCoveredDelta()).isEqualTo(2);
        assertThat(updated.durationMillis()).isEqualTo(41230L);
        assertThat(updated.lastError()).isNull();
    }

    @Test
    void aFailedOutcomeStillRecordsItsErrorAndDiscardedCandidates() {
        WorkUnit unit = WorkUnit.pending(id, "PaymentServiceTest.java", "PaymentService.java", "sha256:src");
        UnitOutcome outcome = UnitOutcome.failed(UnitStatus.FAILED_COMPILE,
                "cannot find symbol: OrderStatus.PARTIAL", List.of("merge refused: duplicate name"));

        WorkUnit updated = WorkUnitOutcomes.apply(unit, outcome, 900L);

        assertThat(updated.status()).isEqualTo(UnitStatus.FAILED_COMPILE);
        assertThat(updated.lastError()).isEqualTo("cannot find symbol: OrderStatus.PARTIAL");
        assertThat(updated.discardedTests()).containsExactly("merge refused: duplicate name");
        assertThat(updated.addedTests()).isEmpty();
    }

    @Test
    void identityAndBookkeepingFieldsNotCarriedByTheOutcomeSurviveUnchanged() {
        WorkUnit unit = WorkUnit.pending(id, "PaymentServiceTest.java", "PaymentService.java", "sha256:src")
                .withAttempts(2).withTestFileHash("sha256:test");
        UnitOutcome outcome = UnitOutcome.kept(List.of("t"), List.of(), List.of(), List.of(), 1, 0);

        WorkUnit updated = WorkUnitOutcomes.apply(unit, outcome, 100L);

        assertThat(updated.id()).isEqualTo(id);
        assertThat(updated.testFile()).isEqualTo("PaymentServiceTest.java");
        assertThat(updated.sourceFile()).isEqualTo("PaymentService.java");
        assertThat(updated.sourceHash()).isEqualTo("sha256:src");
        assertThat(updated.testFileHash()).isEqualTo("sha256:test");
        assertThat(updated.attempts()).isEqualTo(2);
    }
}
