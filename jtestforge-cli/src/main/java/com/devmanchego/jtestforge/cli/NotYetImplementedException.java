package com.devmanchego.jtestforge.cli;

/**
 * Marks a subcommand as scaffolded but not yet wired to its engine.
 *
 * <p>Temporary: every use site is removed as the corresponding implementation phase
 * (see jtestforge-implementation-plan.md) lands. Kept as a distinct exception type
 * rather than a bare message so it cannot be confused with a real preflight failure.
 */
public final class NotYetImplementedException extends RuntimeException {

    public NotYetImplementedException(String commandName, int implementationPhase) {
        super("'%s' is not implemented yet (planned for implementation phase %d)."
                .formatted(commandName, implementationPhase));
    }
}
