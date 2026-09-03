package com.devmanchego.jtestforge.state;

import java.nio.file.Path;

/**
 * Thrown when a state file exists but cannot be trusted: unparseable JSON (typically a
 * write truncated by a kill), or a schema version this build does not understand.
 *
 * <p>Deliberately distinct from "no state file at all". An absent file means a fresh run;
 * a corrupt one must stop the run and say so. Silently treating corruption as "start
 * over" would discard a completed run's bookkeeping without anyone noticing - the failure
 * mode jtestforge-specification.md §8.2.2 exists to prevent.
 */
public final class StateCorruptException extends RuntimeException {

    private final transient Path stateFile;

    public StateCorruptException(Path stateFile, String message, Throwable cause) {
        super(message, cause);
        this.stateFile = stateFile;
    }

    public StateCorruptException(Path stateFile, String message) {
        this(stateFile, message, null);
    }

    public Path stateFile() {
        return stateFile;
    }
}
