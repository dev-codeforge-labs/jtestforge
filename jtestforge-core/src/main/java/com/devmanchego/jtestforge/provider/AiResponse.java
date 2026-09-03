package com.devmanchego.jtestforge.provider;

import java.util.Objects;

/**
 * A completed AI CLI invocation — jtestforge-specification.md §12.1.
 *
 * @param content        the raw stdout text, unparsed
 * @param durationMillis wall-clock time the invocation took, including any transport
 *                       retries - feeds the run report's latency accounting (§15)
 */
public record AiResponse(String content, long durationMillis) {

    public AiResponse {
        Objects.requireNonNull(content, "content");
    }
}
