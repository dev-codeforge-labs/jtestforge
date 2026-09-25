package com.devmanchego.jtestforge.config;

import java.util.Map;

/**
 * {@code aiProvider} block — jtestforge-specification.md §5. {@code active} names the
 * key in {@code providers} to use; it has no default, since which provider runs is a
 * deliberate choice {@link ConfigValidator} enforces is actually present.
 *
 * <p>{@code isolateWorkingDirectory} defaults to {@code true}: see
 * {@code ProviderWorkingDirectory} for why an agentic CLI must not be pointed at the
 * target module.
 */
public record AiProviderConfig(
        String active,
        Map<String, ProviderConfig> providers,
        Boolean isolateWorkingDirectory) {

    public AiProviderConfig {
        providers = providers == null ? Map.of() : Map.copyOf(providers);
        isolateWorkingDirectory = isolateWorkingDirectory == null ? Boolean.TRUE : isolateWorkingDirectory;
    }
}
