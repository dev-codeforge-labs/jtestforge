package com.devmanchego.jtestforge.provider;

import java.time.Duration;

/**
 * A source of AI-generated text — jtestforge-specification.md §12.1.
 *
 * <p>A single {@link ProcessAiProvider} implementation covers both Claude CLI and
 * Gemini CLI; they differ only in configuration. The SPI exists so a future in-process
 * or HTTP provider can be added without touching the orchestration layer, and so tests
 * can inject {@link RecordedAiProvider} instead of a real CLI.
 */
public interface AiProvider {

    /** Identifies this provider in logs, transcripts and the run report (e.g. "claude"). */
    String id();

    /**
     * @throws ProviderException a transport failure that survived every configured retry
     */
    AiResponse invoke(String prompt, Duration timeout) throws ProviderException;
}
