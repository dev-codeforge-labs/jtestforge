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
 */
public record ProviderConfig(
        String command,
        List<String> args,
        PromptDelivery promptDelivery,
        Integer timeoutSeconds,
        Integer transportRetries,
        Map<String, String> env) {

    public ProviderConfig {
        args = args == null ? List.of() : List.copyOf(args);
        promptDelivery = promptDelivery == null ? PromptDelivery.STDIN : promptDelivery;
        timeoutSeconds = timeoutSeconds == null ? 300 : timeoutSeconds;
        transportRetries = transportRetries == null ? 2 : transportRetries;
        env = env == null ? Map.of() : Map.copyOf(env);
    }
}
