package com.devmanchego.jtestforge.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Objects;

/**
 * One unit of work and its outcome — jtestforge-specification.md §8.
 *
 * <p>Immutable, per §17. State changes produce a new instance through the {@code with*}
 * methods, which is what lets {@code StateStore} treat "the state as of now" as a single
 * value it can persist atomically.
 *
 * <p>A unit records <b>both</b> files it depends on, and a hash of the production one.
 * That pairing is what makes resume reconciliation possible (§8.2.3): it is how a resumed
 * run distinguishes "this unit's recorded outcome is still valid" from "a file changed
 * underneath us and the outcome is stale". The source path is stored rather than derived
 * from the class name, because derivation breaks on nested and inner classes - and a
 * derivation that silently resolves to the wrong file would report a unit as valid
 * without ever having checked the class it was generated against.
 *
 * @param id                  identity, including tier and (pass 2) mutant group
 * @param testFile            module-relative path of the test file this unit writes to
 * @param sourceFile          module-relative path of the production source under test
 * @param sourceHash          hash of the production source file when the unit was recorded
 * @param testFileHash        hash of the test file after the unit's tests were merged
 * @param status              current lifecycle state
 * @param attempts            how many generation attempts have been made
 * @param addedTests          names of test methods this unit added and kept
 * @param addedImports        imports this unit introduced. Recorded because an exact
 *                            revert cannot otherwise tell an import the unit added from
 *                            one the developer already had and never used - pruning the
 *                            latter would edit a file the unit never touched
 * @param discardedTests      names of candidates rejected, for the report's reason breakdown
 * @param semanticGapsClosed  framework-semantic gaps (§7.5) this unit closed
 * @param mutantsKilled       pass 2: mutants that went from SURVIVED to KILLED
 * @param linesCoveredDelta   lines newly covered by this unit's tests
 * @param branchesCoveredDelta branches newly covered by this unit's tests
 * @param durationMillis      wall-clock time spent on this unit
 * @param lastError           short description of the most recent failure, if any
 * @param skipReason          why the unit was skipped, for the SKIPPED_* statuses
 * @param createdTestFile     whether this unit wrote {@code testFile} as a new skeleton class.
 *                            Journalled before the skeleton is written, like {@code addedTests}
 *                            (see {@code UnitEditJournal}), so that a unit killed mid-way - most
 *                            often during the AI call, before it merged anything - does not
 *                            leave an empty test class behind. Only meaningful while the unit's
 *                            edits are still to be undone: a finished unit cleaned up after itself
 */
@JsonInclude(JsonInclude.Include.NON_DEFAULT)
public record WorkUnit(
        WorkUnitId id,
        String testFile,
        String sourceFile,
        String sourceHash,
        String testFileHash,
        UnitStatus status,
        int attempts,
        List<String> addedTests,
        List<String> addedImports,
        List<String> discardedTests,
        List<String> semanticGapsClosed,
        List<String> mutantsKilled,
        int linesCoveredDelta,
        int branchesCoveredDelta,
        long durationMillis,
        String lastError,
        String skipReason,
        boolean createdTestFile) {

    public WorkUnit {
        Objects.requireNonNull(id, "id");
        status = status == null ? UnitStatus.PENDING : status;
        addedTests = addedTests == null ? List.of() : List.copyOf(addedTests);
        addedImports = addedImports == null ? List.of() : List.copyOf(addedImports);
        discardedTests = discardedTests == null ? List.of() : List.copyOf(discardedTests);
        semanticGapsClosed = semanticGapsClosed == null ? List.of() : List.copyOf(semanticGapsClosed);
        mutantsKilled = mutantsKilled == null ? List.of() : List.copyOf(mutantsKilled);
    }

    /** The shape this record had before {@code createdTestFile} was journalled. */
    public WorkUnit(WorkUnitId id, String testFile, String sourceFile, String sourceHash, String testFileHash,
                    UnitStatus status, int attempts, List<String> addedTests, List<String> addedImports,
                    List<String> discardedTests, List<String> semanticGapsClosed, List<String> mutantsKilled,
                    int linesCoveredDelta, int branchesCoveredDelta, long durationMillis, String lastError,
                    String skipReason) {
        this(id, testFile, sourceFile, sourceHash, testFileHash, status, attempts, addedTests, addedImports,
                discardedTests, semanticGapsClosed, mutantsKilled, linesCoveredDelta, branchesCoveredDelta,
                durationMillis, lastError, skipReason, false);
    }

    /** A freshly discovered unit, not yet attempted. */
    public static WorkUnit pending(WorkUnitId id, String testFile, String sourceFile, String sourceHash) {
        return new WorkUnit(id, testFile, sourceFile, sourceHash, null, UnitStatus.PENDING,
                0, null, null, null, null, null, 0, 0, 0L, null, null);
    }

    /** Convenience accessors delegating to the id, so callers rarely need to unwrap it. */
    public String className() {
        return id.className();
    }

    public String method() {
        return id.methodSignature();
    }

    public Tier tier() {
        return id.tier();
    }

    public WorkUnit withStatus(UnitStatus newStatus) {
        return new WorkUnit(id, testFile, sourceFile, sourceHash, testFileHash, newStatus, attempts,
                addedTests, addedImports, discardedTests, semanticGapsClosed, mutantsKilled,
                linesCoveredDelta, branchesCoveredDelta, durationMillis, lastError, skipReason, createdTestFile);
    }

    public WorkUnit withStatusAndError(UnitStatus newStatus, String error) {
        return new WorkUnit(id, testFile, sourceFile, sourceHash, testFileHash, newStatus, attempts,
                addedTests, addedImports, discardedTests, semanticGapsClosed, mutantsKilled,
                linesCoveredDelta, branchesCoveredDelta, durationMillis, error, skipReason, createdTestFile);
    }

    public WorkUnit withSkipped(UnitStatus newStatus, String reason) {
        return new WorkUnit(id, testFile, sourceFile, sourceHash, testFileHash, newStatus, attempts,
                addedTests, addedImports, discardedTests, semanticGapsClosed, mutantsKilled,
                linesCoveredDelta, branchesCoveredDelta, durationMillis, lastError, reason, createdTestFile);
    }

    public WorkUnit withAttempts(int newAttempts) {
        return new WorkUnit(id, testFile, sourceFile, sourceHash, testFileHash, status, newAttempts,
                addedTests, addedImports, discardedTests, semanticGapsClosed, mutantsKilled,
                linesCoveredDelta, branchesCoveredDelta, durationMillis, lastError, skipReason, createdTestFile);
    }

    public WorkUnit withTestFileHash(String newTestFileHash) {
        return new WorkUnit(id, testFile, sourceFile, sourceHash, newTestFileHash, status, attempts,
                addedTests, addedImports, discardedTests, semanticGapsClosed, mutantsKilled,
                linesCoveredDelta, branchesCoveredDelta, durationMillis, lastError, skipReason, createdTestFile);
    }

    public WorkUnit withAdded(List<String> newAddedTests, List<String> newAddedImports) {
        return new WorkUnit(id, testFile, sourceFile, sourceHash, testFileHash, status, attempts,
                newAddedTests, newAddedImports, discardedTests, semanticGapsClosed, mutantsKilled,
                linesCoveredDelta, branchesCoveredDelta, durationMillis, lastError, skipReason, createdTestFile);
    }

    public WorkUnit withCreatedTestFile(boolean newCreatedTestFile) {
        return new WorkUnit(id, testFile, sourceFile, sourceHash, testFileHash, status, attempts,
                addedTests, addedImports, discardedTests, semanticGapsClosed, mutantsKilled,
                linesCoveredDelta, branchesCoveredDelta, durationMillis, lastError, skipReason, newCreatedTestFile);
    }

    /**
     * Whether this finished unit still names test-file edits nobody undid. A {@code DONE} unit's
     * {@code addedTests} are its kept tests; every other finished status is written with empty
     * lists and no {@code createdTestFile}, so anything recorded means the unit's own rollback
     * failed and the edits are still in the file. {@code IN_PROGRESS} units are not covered: an
     * interrupted unit is reverted whatever it recorded.
     */
    public boolean hasLeftoverEdits() {
        return status != UnitStatus.DONE && status != UnitStatus.IN_PROGRESS
                && (!addedTests.isEmpty() || !addedImports.isEmpty() || createdTestFile);
    }

    public WorkUnit withAddedTests(List<String> newAddedTests) {
        return new WorkUnit(id, testFile, sourceFile, sourceHash, testFileHash, status, attempts,
                newAddedTests, addedImports, discardedTests, semanticGapsClosed, mutantsKilled,
                linesCoveredDelta, branchesCoveredDelta, durationMillis, lastError, skipReason, createdTestFile);
    }

    /**
     * Resets this unit to {@code PENDING}, clearing everything the previous attempt
     * recorded. Used by resume reconciliation (§8.2.3): a unit whose recorded outcome no
     * longer matches the filesystem must be re-attempted from a clean slate, not from
     * half-trusted bookkeeping.
     */
    public WorkUnit resetToPending() {
        return new WorkUnit(id, testFile, sourceFile, sourceHash, null, UnitStatus.PENDING,
                0, null, null, null, null, null, 0, 0, 0L, null, null);
    }
}
