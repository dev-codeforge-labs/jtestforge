package com.devmanchego.jtestforge.orchestration;

import com.devmanchego.jtestforge.analysis.TestClassReverter;
import com.devmanchego.jtestforge.model.RunState;
import com.devmanchego.jtestforge.model.UnitStatus;
import com.devmanchego.jtestforge.model.WorkUnit;
import com.devmanchego.jtestforge.state.LockFile;
import com.devmanchego.jtestforge.state.StateStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * Cleans up after an interrupted run — jtestforge-specification.md §14.1: "a shutdown
 * hook flushes state, releases the lock, and reverts any {@code IN_PROGRESS} unit's
 * partial file edits so the working tree is never left mid-merge."
 *
 * <p><b>Best-effort by design, backed by a robust fallback.</b> This class can only
 * revert what the state file <em>already recorded</em> for the interrupted unit -
 * {@code addedTests}/{@code addedImports} as of the last successful outcome write, not
 * necessarily whatever a mid-flight merge attempt has on disk at the exact instant the
 * process dies. That gap is not a bug to close here: resume reconciliation (§8.2.3)
 * independently re-parses every {@code DONE} unit's test file against what the state file
 * claims and resets anything that does not match, which is what makes "the filesystem is
 * the truth, not the JSON" hold regardless of what this handler managed to clean up.
 *
 * <p>Registered as a JVM shutdown hook via {@link #install()}. {@link #handleInterrupt()}
 * is also called directly by tests to simulate an interrupt deterministically - a real
 * {@code SIGINT} is not something a unit test can trigger reliably.
 */
public final class InterruptHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(InterruptHandler.class);

    private final StateStore stateStore;
    private final LockFile lockFile;
    private final TestClassReverter reverter;
    private final Path modulePath;

    public InterruptHandler(StateStore stateStore, LockFile lockFile, TestClassReverter reverter, Path modulePath) {
        this.stateStore = Objects.requireNonNull(stateStore, "stateStore");
        this.lockFile = Objects.requireNonNull(lockFile, "lockFile");
        this.reverter = Objects.requireNonNull(reverter, "reverter");
        this.modulePath = Objects.requireNonNull(modulePath, "modulePath");
    }

    /** Registers the cleanup as a JVM shutdown hook and returns it, so a caller can also remove it. */
    public Thread install() {
        Thread hook = new Thread(this::handleInterrupt, "jtestforge-interrupt-handler");
        Runtime.getRuntime().addShutdownHook(hook);
        return hook;
    }

    /** Removes a previously installed hook - e.g. once a run finishes normally. */
    public void uninstall(Thread hook) {
        try {
            Runtime.getRuntime().removeShutdownHook(hook);
        } catch (IllegalStateException e) {
            // The JVM is already shutting down; the hook will run (or already has) regardless.
        }
    }

    /**
     * The cleanup itself: revert every {@code IN_PROGRESS} unit's recorded edits, then
     * release the lock. State is already flushed continuously by {@link StateStore}'s
     * write-ahead persistence (§8.2.1); nothing here needs to write it again.
     */
    public void handleInterrupt() {
        Optional<RunState> state = stateStore.load();
        if (state.isEmpty()) {
            lockFile.release();
            return;
        }
        for (WorkUnit unit : state.get().units()) {
            if (unit.status() == UnitStatus.IN_PROGRESS) {
                revertPartialEdits(unit);
            }
        }
        lockFile.release();
    }

    private void revertPartialEdits(WorkUnit unit) {
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
