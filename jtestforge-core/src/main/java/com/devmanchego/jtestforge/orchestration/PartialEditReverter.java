package com.devmanchego.jtestforge.orchestration;

import com.devmanchego.jtestforge.analysis.TestClassReverter;
import com.devmanchego.jtestforge.analysis.TestClassScanner;
import com.devmanchego.jtestforge.model.RunState;
import com.devmanchego.jtestforge.model.UnitStatus;
import com.devmanchego.jtestforge.model.WorkUnit;
import com.devmanchego.jtestforge.model.WorkUnitId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Collection;
import java.util.Objects;

/**
 * Takes out of a test file whatever an interrupted unit had merged into it, as recorded in the
 * state file - used by the shutdown hook ({@link InterruptHandler}), by resume once the next run
 * has reconciled the state (jtestforge-specification.md §8.2.3), and by {@code --restart} before
 * it discards the state. The same applies to a finished unit whose own rollback failed
 * ({@link WorkUnit#hasLeftoverEdits()}).
 *
 * <p><b>Always hand it the state as loaded from disk.</b> {@code ResumeReconciler} resets every
 * interrupted unit with {@code WorkUnit.resetToPending()}, which clears {@code addedTests} and
 * {@code addedImports} - exactly the lists this class needs. Passing the reconciled state
 * reverts nothing, silently: that was the behaviour of resume until the write-ahead journal
 * ({@link UnitEditJournal}) made those lists worth reading.
 */
public final class PartialEditReverter {

    private static final Logger LOGGER = LoggerFactory.getLogger(PartialEditReverter.class);

    private final TestClassReverter reverter;
    private final TestClassScanner scanner;
    private final Path modulePath;

    public PartialEditReverter(TestClassReverter reverter, Path modulePath) {
        this.reverter = Objects.requireNonNull(reverter, "reverter");
        this.scanner = new TestClassScanner(reverter.charset());
        this.modulePath = Objects.requireNonNull(modulePath, "modulePath");
    }

    /**
     * @param loadedState the state as read from disk, <em>before</em> reconciliation
     * @param unitIds     the units to revert, e.g. {@code ReconciliationResult.unitsNeedingRevert()}
     */
    public void revert(RunState loadedState, Collection<WorkUnitId> unitIds) {
        for (WorkUnitId unitId : unitIds) {
            loadedState.unit(unitId).ifPresent(this::revert);
        }
    }

    /** Reverts every unit of {@code state} that was interrupted or could not undo its own edits. */
    public void revertUnfinished(RunState state) {
        for (WorkUnit unit : state.units()) {
            if (unit.status() == UnitStatus.IN_PROGRESS || unit.hasLeftoverEdits()) {
                revert(unit);
            }
        }
    }

    /**
     * Reverts one unit's recorded edits, then deletes the test class if the unit created it and
     * nothing is left in it. A unit that recorded nothing is left alone.
     *
     * <p>Unlike the unit's own failure path, a class where a mock bean was synthesised is deleted
     * too: that one is kept within a run because the class's single escalation is spent and a
     * fresh skeleton could not be escalated again; a later run starts with a new budget.
     */
    public void revert(WorkUnit unit) {
        Path testFile = ModulePaths.resolve(modulePath, unit.testFile());
        if (!unit.addedTests().isEmpty() || !unit.addedImports().isEmpty()) {
            LOGGER.warn("Reverting partial edits from interrupted unit {} in {}", unit.id(), testFile);
            reverter.revert(testFile, unit.addedTests(), unit.addedImports());
        }
        if (unit.createdTestFile() && EmptyTestClassCleanup.deleteIfNoTests(scanner, testFile)) {
            LOGGER.warn("Removed the empty test class {} created by interrupted unit {}", testFile, unit.id());
        }
    }
}
