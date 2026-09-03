package com.devmanchego.jtestforge.state;

import com.devmanchego.jtestforge.model.RunState;
import com.devmanchego.jtestforge.model.WorkUnit;
import com.devmanchego.jtestforge.model.WorkUnitId;
import com.devmanchego.jtestforge.util.FileHasher;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Reconciles a loaded run state against what is actually on disk before a resumed run is
 * allowed to trust it — jtestforge-specification.md §8.2.3.
 *
 * <p>The principle this class enforces: <b>the filesystem is the truth, not the JSON.</b>
 * A {@code DONE} unit only stays done if its recorded tests are still present in the test
 * file and the production class it was generated against has not changed. Anything else
 * is reset to {@code PENDING}.
 *
 * <p>The failure this prevents is silent and expensive: a state file that claims work is
 * finished when someone has since reverted the branch, deleted a generated test, or
 * changed the class under test means the tool skips that work forever, and nobody notices
 * it is being skipped.
 *
 * <p>Reverting the partial edits of units caught {@code IN_PROGRESS} is <em>not</em> done
 * here. This class decides what is stale; undoing file edits belongs to the component
 * that owns the test-file lifecycle ({@code TestClassReverter}, implementation phase 5).
 * The units needing that treatment are surfaced in {@link ReconciliationResult} so the
 * ordering - reconcile, then revert, then resume - is explicit at the call site.
 */
public final class ResumeReconciler {

    private final TestFileInspector testFileInspector;

    public ResumeReconciler(TestFileInspector testFileInspector) {
        this.testFileInspector = Objects.requireNonNull(testFileInspector, "testFileInspector");
    }

    /**
     * @param state             the state as loaded from disk
     * @param moduleRoot        module directory, for resolving the units' relative paths
     * @param currentConfigHash hash of the config this run is using
     */
    public ReconciliationResult reconcile(RunState state, Path moduleRoot, String currentConfigHash) {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(moduleRoot, "moduleRoot");

        List<ReconciliationResult.Reset> resets = new ArrayList<>();
        List<WorkUnitId> unitsNeedingRevert = new ArrayList<>();
        List<WorkUnit> reconciledUnits = new ArrayList<>(state.units().size());

        for (WorkUnit unit : state.units()) {
            switch (unit.status()) {
                case IN_PROGRESS -> {
                    // The write-ahead marker (§8.2.1) did its job: a previous run died
                    // here, so whatever it had already written to the test file is a
                    // partial edit that must come out before work resumes.
                    unitsNeedingRevert.add(unit.id());
                    resets.add(new ReconciliationResult.Reset(unit.id(),
                            "a previous run was killed while this unit was in progress"));
                    reconciledUnits.add(unit.resetToPending());
                }
                case DONE -> {
                    Optional<String> staleReason = staleReason(unit, moduleRoot);
                    if (staleReason.isPresent()) {
                        resets.add(new ReconciliationResult.Reset(unit.id(), staleReason.get()));
                        reconciledUnits.add(unit.resetToPending());
                    } else {
                        reconciledUnits.add(unit);
                    }
                }
                default -> {
                    // Whether the FAILED_* and DISCARDED_* statuses are retried is the
                    // --retry-failed decision (§8.1), made by the engine when it selects
                    // work. Reconciliation must not pre-empt it.
                    reconciledUnits.add(unit);
                }
            }
        }

        boolean configChanged = !Objects.equals(state.configHash(), currentConfigHash);
        RunState reconciled = state.withUnits(reconciledUnits);
        if (configChanged) {
            // Completed work survives a config change - those tests exist and still pass.
            // Only the measurements they were judged against are stale.
            reconciled = reconciled.withBaseline(null).withConfigHash(currentConfigHash);
        }

        return new ReconciliationResult(reconciled, resets, unitsNeedingRevert, configChanged);
    }

    /** @return why this DONE unit can no longer be trusted, or empty if it still can. */
    private Optional<String> staleReason(WorkUnit unit, Path moduleRoot) {
        Optional<String> sourceProblem = sourceFileProblem(unit, moduleRoot);
        if (sourceProblem.isPresent()) {
            return sourceProblem;
        }
        return missingTestsProblem(unit, moduleRoot);
    }

    private Optional<String> sourceFileProblem(WorkUnit unit, Path moduleRoot) {
        if (unit.sourceFile() == null || unit.sourceHash() == null) {
            return Optional.empty();
        }
        Path sourcePath = moduleRoot.resolve(unit.sourceFile());
        Optional<String> currentHash;
        try {
            currentHash = FileHasher.hash(sourcePath);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to hash " + sourcePath, e);
        }
        if (currentHash.isEmpty()) {
            return Optional.of("the production source file no longer exists: " + unit.sourceFile());
        }
        if (!currentHash.get().equals(unit.sourceHash())) {
            return Optional.of("the production class changed since these tests were generated");
        }
        return Optional.empty();
    }

    private Optional<String> missingTestsProblem(WorkUnit unit, Path moduleRoot) {
        if (unit.testFile() == null || unit.addedTests().isEmpty()) {
            return Optional.empty();
        }
        Set<String> presentTests = testFileInspector.testMethodNames(moduleRoot.resolve(unit.testFile()));
        List<String> missing = unit.addedTests().stream()
                .filter(testName -> !presentTests.contains(testName))
                .toList();
        if (missing.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of("test methods recorded by this unit are no longer in the test file: "
                + String.join(", ", missing));
    }
}
