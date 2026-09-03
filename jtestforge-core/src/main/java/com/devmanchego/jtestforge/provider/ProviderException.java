package com.devmanchego.jtestforge.provider;

/**
 * Thrown when an {@link AiProvider} cannot produce a response at all —
 * jtestforge-specification.md §12.1: a transport failure that survived every configured
 * retry (§12.2). Never thrown for a well-formed but useless answer; that is the response
 * parser's and the quality gates' concern, not the provider's.
 */
public final class ProviderException extends Exception {

    public ProviderException(String message) {
        super(message);
    }

    public ProviderException(String message, Throwable cause) {
        super(message, cause);
    }
}
