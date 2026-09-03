package com.devmanchego.jtestforge.mutation;

/**
 * A {@link MutationRunner} could not produce a report - the engine failed to launch, the
 * report file never appeared, or it could not be parsed. Checked, like
 * {@link com.devmanchego.jtestforge.provider.ProviderException}: a mutation run is an
 * external-process boundary the caller must decide how to react to (retry, abort,
 * surface), not a programming error.
 */
public final class MutationException extends Exception {

    public MutationException(String message) {
        super(message);
    }

    public MutationException(String message, Throwable cause) {
        super(message, cause);
    }
}
