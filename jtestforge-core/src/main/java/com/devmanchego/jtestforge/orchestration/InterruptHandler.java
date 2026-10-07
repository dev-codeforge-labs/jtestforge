package com.devmanchego.jtestforge.orchestration;

import com.devmanchego.jtestforge.analysis.TestClassReverter;
import com.devmanchego.jtestforge.model.RunState;
import com.devmanchego.jtestforge.model.UnitStatus;
import com.devmanchego.jtestforge.model.WorkUnit;
import com.devmanchego.jtestforge.state.LockFile;
import com.devmanchego.jtestforge.state.StateStore;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * Cleans up after an interrupted run — jtestforge-specification.md §14.1: "a shutdown
 * hook flushes state, releases the lock, and reverts any {@code IN_PROGRESS} unit's
 * partial file edits so the working tree is never left mid-merge."
 *
 * <p>What to revert comes from the state file, which a unit updates <em>before</em> each
 * edit it makes to its test file ({@link UnitEditJournal}) - so whatever instant the
 * process dies at, the interrupted unit names every method it may have merged. Reverting
 * a method that never made it to the file is a no-op.
 *
 * <p><b>Still best-effort, backed by resume.</b> The hook runs on its own thread while the
 * main thread may still be mid-write, so it can revert a file the main thread then writes
 * again before the JVM halts. That race is deliberately not locked against: the unit stays
 * {@code IN_PROGRESS} with its journal intact, and the next run's resume reverts it again
 * through {@link PartialEditReverter} - which is what makes "the filesystem is the truth, not
 * the JSON" hold regardless of what this handler managed to clean up.
 *
 * <p>Registered as a JVM shutdown hook via {@link #install()}. {@link #handleInterrupt()}
 * is also called directly by tests to simulate an interrupt deterministically - a real
 * {@code SIGINT} is not something a unit test can trigger reliably.
 */
public final class InterruptHandler {

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
        new PartialEditReverter(reverter, modulePath).revert(unit);
    }
}
