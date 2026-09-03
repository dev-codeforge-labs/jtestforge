package com.devmanchego.jtestforge.cli;

import com.devmanchego.jtestforge.model.Phase;
import com.devmanchego.jtestforge.model.RunState;
import com.devmanchego.jtestforge.model.SpringTierState;
import com.devmanchego.jtestforge.orchestration.GenerateResult;
import com.devmanchego.jtestforge.orchestration.HardenResult;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * jtestforge-specification.md §14.1: every engine-driven exit code, produced from a
 * targeted {@link GenerateResult}/{@link HardenResult} - the plan's own test criterion for
 * this phase, applied to the codes the two pass engines are responsible for.
 */
class ExitCodeMapperTest {

    private static final RunState STATE = RunState.startNew("run-1", Instant.parse("2026-08-31T10:00:00Z"),
            Phase.GENERATE, "C:/app", "sha256:cfg", "claude", SpringTierState.springDisabled(40), List.of());

    @Test
    void generateCompletedMapsToZero() {
        assertThat(ExitCodeMapper.forGenerate(
                new GenerateResult(STATE, GenerateResult.ExitReason.COMPLETED, List.of())))
                .isEqualTo(0);
    }

    @Test
    void generatePreflightFailedMapsToOne() {
        assertThat(ExitCodeMapper.forGenerate(
                new GenerateResult(STATE, GenerateResult.ExitReason.PREFLIGHT_FAILED, List.of())))
                .isEqualTo(1);
    }

    @Test
    void generateNothingKeptMapsToThree() {
        assertThat(ExitCodeMapper.forGenerate(
                new GenerateResult(STATE, GenerateResult.ExitReason.NOTHING_KEPT, List.of())))
                .isEqualTo(3);
    }

    @Test
    void generateProviderUnreachableMapsToFour() {
        assertThat(ExitCodeMapper.forGenerate(
                new GenerateResult(STATE, GenerateResult.ExitReason.PROVIDER_UNREACHABLE, List.of())))
                .isEqualTo(4);
    }

    @Test
    void generateAbortedOnConsecutiveFailuresMapsToFive() {
        assertThat(ExitCodeMapper.forGenerate(
                new GenerateResult(STATE, GenerateResult.ExitReason.ABORTED_ON_CONSECUTIVE_FAILURES, List.of())))
                .isEqualTo(5);
    }

    @Test
    void generateFullSuiteRedMapsToSix() {
        assertThat(ExitCodeMapper.forGenerate(
                new GenerateResult(STATE, GenerateResult.ExitReason.FULL_SUITE_RED, List.of())))
                .isEqualTo(6);
    }

    @Test
    void generateContextLoadBudgetExhaustedMapsToEight() {
        assertThat(ExitCodeMapper.forGenerate(
                new GenerateResult(STATE, GenerateResult.ExitReason.CONTEXT_LOAD_BUDGET_EXHAUSTED, List.of())))
                .isEqualTo(8);
    }

    @Test
    void hardenCompletedMapsToZero() {
        assertThat(ExitCodeMapper.forHarden(
                new HardenResult(STATE, HardenResult.ExitReason.COMPLETED, List.of())))
                .isEqualTo(0);
    }

    @Test
    void hardenPreflightFailedMapsToOne() {
        assertThat(ExitCodeMapper.forHarden(
                new HardenResult(STATE, HardenResult.ExitReason.PREFLIGHT_FAILED, List.of())))
                .isEqualTo(1);
    }

    @Test
    void hardenNothingKeptMapsToThree() {
        assertThat(ExitCodeMapper.forHarden(
                new HardenResult(STATE, HardenResult.ExitReason.NOTHING_KEPT, List.of())))
                .isEqualTo(3);
    }

    @Test
    void hardenProviderUnreachableMapsToFour() {
        assertThat(ExitCodeMapper.forHarden(
                new HardenResult(STATE, HardenResult.ExitReason.PROVIDER_UNREACHABLE, List.of())))
                .isEqualTo(4);
    }

    @Test
    void hardenFullSuiteRedMapsToSix() {
        assertThat(ExitCodeMapper.forHarden(
                new HardenResult(STATE, HardenResult.ExitReason.FULL_SUITE_RED, List.of())))
                .isEqualTo(6);
    }

    @Test
    void hardenMutationScoreBelowThresholdMapsToSeven() {
        assertThat(ExitCodeMapper.forHarden(
                new HardenResult(STATE, HardenResult.ExitReason.MUTATION_SCORE_BELOW_THRESHOLD, List.of())))
                .isEqualTo(7);
    }
}
