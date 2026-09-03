package com.devmanchego.jtestforge.cli;

import com.devmanchego.jtestforge.orchestration.GenerateResult;
import com.devmanchego.jtestforge.orchestration.HardenResult;

/**
 * Maps an engine's own outcome to the numeric exit codes of jtestforge-specification.md
 * §14.1 - the one place that translation happens, so {@code GenerateCommand}/
 * {@code HardenCommand} (wired once full unit discovery lands - jtestforge-implementation-plan.md
 * phase 18) need only ask "what code does this result mean" rather than repeat the table.
 */
final class ExitCodeMapper {

    private ExitCodeMapper() {
    }

    static int forGenerate(GenerateResult result) {
        return switch (result.exitReason()) {
            case COMPLETED -> ExitCodes.SUCCESS;
            case PREFLIGHT_FAILED -> ExitCodes.CONFIGURATION_OR_PREFLIGHT_ERROR;
            case NOTHING_KEPT -> ExitCodes.NOTHING_KEPT;
            case PROVIDER_UNREACHABLE -> ExitCodes.PROVIDER_UNREACHABLE;
            case ABORTED_ON_CONSECUTIVE_FAILURES -> ExitCodes.ABORTED_ON_CONSECUTIVE_FAILURES;
            case FULL_SUITE_RED -> ExitCodes.FULL_SUITE_RED;
            case CONTEXT_LOAD_BUDGET_EXHAUSTED -> ExitCodes.CONTEXT_LOAD_BUDGET_EXHAUSTED;
        };
    }

    static int forHarden(HardenResult result) {
        return switch (result.exitReason()) {
            case COMPLETED -> ExitCodes.SUCCESS;
            case PREFLIGHT_FAILED -> ExitCodes.CONFIGURATION_OR_PREFLIGHT_ERROR;
            case NOTHING_KEPT -> ExitCodes.NOTHING_KEPT;
            case PROVIDER_UNREACHABLE -> ExitCodes.PROVIDER_UNREACHABLE;
            case FULL_SUITE_RED -> ExitCodes.FULL_SUITE_RED;
            case MUTATION_SCORE_BELOW_THRESHOLD -> ExitCodes.MUTATION_SCORE_BELOW_THRESHOLD;
        };
    }
}
