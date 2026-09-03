package com.devmanchego.jtestforge.config;

/** {@code generate} block — jtestforge-specification.md §5, §9. */
public record GenerateConfig(
        Integer maxTestsPerMethod,
        Integer maxRepairAttempts,
        Boolean requireCoverageGain,
        Boolean requireAssertion,
        Boolean rejectMockOnlyTests) {

    public GenerateConfig {
        maxTestsPerMethod = maxTestsPerMethod == null ? 5 : maxTestsPerMethod;
        maxRepairAttempts = maxRepairAttempts == null ? 2 : maxRepairAttempts;
        requireCoverageGain = requireCoverageGain == null ? Boolean.TRUE : requireCoverageGain;
        requireAssertion = requireAssertion == null ? Boolean.TRUE : requireAssertion;
        rejectMockOnlyTests = rejectMockOnlyTests == null ? Boolean.TRUE : rejectMockOnlyTests;
    }
}
