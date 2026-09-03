package com.devmanchego.jtestforge.config;

/** {@code execution} block — jtestforge-specification.md §5, §8.3. */
public record ExecutionConfig(
        String stateDir,
        Boolean backupOriginalTests,
        Boolean dryRun,
        Integer consecutiveFailureAbort,
        Integer maxUnitsPerRun) {

    public ExecutionConfig {
        stateDir = stateDir == null ? ".jtestforge" : stateDir;
        backupOriginalTests = backupOriginalTests == null ? Boolean.TRUE : backupOriginalTests;
        dryRun = dryRun == null ? Boolean.FALSE : dryRun;
        consecutiveFailureAbort = consecutiveFailureAbort == null ? 5 : consecutiveFailureAbort;
        // 0 means unlimited (spec §5) - unlike the other counters, 0 is a valid,
        // meaningful configured value here, not just "absent".
        maxUnitsPerRun = maxUnitsPerRun == null ? 0 : maxUnitsPerRun;
    }
}
