package com.devmanchego.jtestforge.state;

/**
 * Thrown when the module's lock is already held - jtestforge-specification.md §8.3.
 * Maps to exit code 2.
 *
 * <p>{@link #isStale()} distinguishes a genuinely concurrent run from a lock left behind
 * by a process that died. Both refuse to proceed; only the stale case is safe to clear
 * with {@code --force-unlock}, and the message says so rather than making the user guess.
 */
public final class LockHeldException extends RuntimeException {

    private final long holderPid;
    private final boolean stale;

    public LockHeldException(long holderPid, boolean stale, String message) {
        super(message);
        this.holderPid = holderPid;
        this.stale = stale;
    }

    public long holderPid() {
        return holderPid;
    }

    /** Whether the holding process is no longer alive, making --force-unlock safe. */
    public boolean isStale() {
        return stale;
    }
}
