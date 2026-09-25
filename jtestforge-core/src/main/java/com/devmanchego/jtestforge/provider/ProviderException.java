package com.devmanchego.jtestforge.provider;

/**
 * Thrown when an {@link AiProvider} cannot produce a response at all —
 * jtestforge-specification.md §12.1: a transport failure that survived every configured
 * retry (§12.2). Never thrown for a well-formed but useless answer; that is the response
 * parser's and the quality gates' concern, not the provider's.
 */
public final class ProviderException extends Exception {

    private final Diagnostics diagnostics;

    public ProviderException(String message) {
        this(message, (Diagnostics) null);
    }

    public ProviderException(String message, Throwable cause) {
        super(message, cause);
        this.diagnostics = null;
    }

    /** @param diagnostics the failed process's full output, or null when no process ran at all */
    public ProviderException(String message, Diagnostics diagnostics) {
        super(message);
        this.diagnostics = diagnostics;
    }

    /** Empty when the failure never reached a process (interrupted, or the executable itself would not start). */
    public java.util.Optional<Diagnostics> diagnostics() {
        return java.util.Optional.ofNullable(diagnostics);
    }

    /**
     * The failed attempt's complete transport-level detail — the whole point being that
     * this is exactly what a truncated, one-line {@link #getMessage()} throws away.
     *
     * @param command  the resolved command that was run, joined for display only
     * @param exitCode the process's exit code, or {@link Integer#MIN_VALUE} on a timeout
     * @param stdout   everything the process wrote to standard output
     * @param stderr   everything the process wrote to standard error
     */
    public record Diagnostics(String command, int exitCode, String stdout, String stderr) {
    }
}
