package com.devmanchego.jtestforge.provider;

import java.util.Objects;

/**
 * A completed AI CLI invocation — jtestforge-specification.md §12.1.
 *
 * @param content        the raw stdout text, unparsed
 * @param durationMillis wall-clock time the invocation took, including any transport
 *                       retries - feeds the run report's latency accounting (§15)
 * @param stderr         whatever the CLI wrote to standard error, kept even on a
 *                       SUCCESSFUL call. An agentic CLI reports its retries, backoff,
 *                       quota limits and tool activity there while still exiting 0, so
 *                       discarding it throws away the only explanation available for a
 *                       call that succeeded but took minutes.
 */
public record AiResponse(String content, long durationMillis, String stderr) {

    public AiResponse {
        Objects.requireNonNull(content, "content");
        stderr = stderr == null ? "" : stderr;
    }

    public AiResponse(String content, long durationMillis) {
        this(content, durationMillis, "");
    }
}
