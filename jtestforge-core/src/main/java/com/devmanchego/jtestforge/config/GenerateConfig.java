package com.devmanchego.jtestforge.config;

/**
 * {@code generate} block — jtestforge-specification.md §5, §9.
 *
 * <p>{@code requireCoverageGain} defaults to {@code false}: a candidate that compiles and
 * passes is kept even when it moves no line/branch coverage. {@code ValueGate} and
 * {@code CoverageAndGapAcceptanceGate} both key off this same flag to skip the
 * {@code jacoco:report} invocation entirely when it is off - not just to ignore the
 * result, since a broken JaCoCo setup on the target module (found in practice) must not
 * block generation when its output was never going to be consulted anyway.
 */
public record GenerateConfig(
        Integer maxTestsPerMethod,
        Integer maxRepairAttempts,
        Boolean requireCoverageGain,
        Boolean requireAssertion,
        Boolean rejectMockOnlyTests) {

    public GenerateConfig {
        maxTestsPerMethod = maxTestsPerMethod == null ? 5 : maxTestsPerMethod;
        maxRepairAttempts = maxRepairAttempts == null ? 2 : maxRepairAttempts;
        requireCoverageGain = requireCoverageGain == null ? Boolean.FALSE : requireCoverageGain;
        requireAssertion = requireAssertion == null ? Boolean.TRUE : requireAssertion;
        rejectMockOnlyTests = rejectMockOnlyTests == null ? Boolean.TRUE : rejectMockOnlyTests;
    }
}
