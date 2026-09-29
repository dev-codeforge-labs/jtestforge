package com.devmanchego.jtestforge.config;

import java.util.List;
import java.util.Map;

/**
 * One entry of {@code aiProvider.providers.*} — jtestforge-specification.md §5, §12.2.
 *
 * @param env variables merged on top of the inherited environment for this CLI only.
 *            Deliberately generic: JTestForge knows nothing about any particular CLI's
 *            variables, but a corporate environment routinely needs some set - a config
 *            directory the user can actually write to, a proxy, a debug switch. API keys
 *            belong in the real environment, never in this file.
 * @param maxPromptChars overrides {@code context.maxPromptChars} for this provider only.
 *                       {@code null} means "use the global value" - most CLIs share one
 *                       reasonable context budget, but a locally hosted model can have a
 *                       far smaller (or larger) one than a hosted CLI, and that is a
 *                       property of the provider, not of the run.
 */
public record ProviderConfig(
        String command,
        List<String> args,
        PromptDelivery promptDelivery,
        Integer timeoutSeconds,
        Integer transportRetries,
        Map<String, String> env,
        Integer maxPromptChars) {

    public ProviderConfig {
        args = args == null ? List.of() : List.copyOf(args);
        promptDelivery = promptDelivery == null ? PromptDelivery.STDIN : promptDelivery;
        timeoutSeconds = timeoutSeconds == null ? 300 : timeoutSeconds;
        transportRetries = transportRetries == null ? 2 : transportRetries;
        env = env == null ? Map.of() : Map.copyOf(env);
    }
}
