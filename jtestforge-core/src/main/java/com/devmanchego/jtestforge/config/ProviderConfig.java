package com.devmanchego.jtestforge.config;

import java.util.List;

/** One entry of {@code aiProvider.providers.*} — jtestforge-specification.md §5, §12.2. */
public record ProviderConfig(
        String command,
        List<String> args,
        PromptDelivery promptDelivery,
        Integer timeoutSeconds,
        Integer transportRetries) {

    public ProviderConfig {
        args = args == null ? List.of() : List.copyOf(args);
        promptDelivery = promptDelivery == null ? PromptDelivery.STDIN : promptDelivery;
        timeoutSeconds = timeoutSeconds == null ? 300 : timeoutSeconds;
        transportRetries = transportRetries == null ? 2 : transportRetries;
    }
}
