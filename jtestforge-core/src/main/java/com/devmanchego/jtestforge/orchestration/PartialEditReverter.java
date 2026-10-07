package com.devmanchego.jtestforge.orchestration;

import com.devmanchego.jtestforge.analysis.TestClassReverter;
import com.devmanchego.jtestforge.model.RunState;
import com.devmanchego.jtestforge.model.WorkUnit;
import com.devmanchego.jtestforge.model.WorkUnitId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Collection;
import java.util.Objects;

/**
 * Takes out of a test file whatever an interrupted unit had merged into it, as recorded in the
 * state file - used by the shutdown hook ({@link InterruptHandler}) and by resume, once the
 * next run has reconciled the state (jtestforge-specification.md §8.2.3).
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
    private final Path modulePath;

    public PartialEditReverter(TestClassReverter reverter, Path modulePath) {
        this.reverter = Objects.requireNonNull(reverter, "reverter");
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

    /** Reverts one unit's recorded edits; a unit that recorded none is left alone. */
    public void revert(WorkUnit unit) {
        if (unit.addedTests().isEmpty() && unit.addedImports().isEmpty()) {
            return;
        }
        Path testFile = resolveAgainstModule(unit.testFile());
        LOGGER.warn("Reverting partial edits from interrupted unit {} in {}", unit.id(), testFile);
        reverter.revert(testFile, unit.addedTests(), unit.addedImports());
    }

    private Path resolveAgainstModule(String testFile) {
        Path path = Path.of(testFile);
        return path.isAbsolute() ? path : modulePath.resolve(path);
    }
}
