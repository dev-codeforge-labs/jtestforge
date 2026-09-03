package com.devmanchego.jtestforge.config;

import java.util.Map;

/**
 * {@code aiProvider} block — jtestforge-specification.md §5. {@code active} names the
 * key in {@code providers} to use; it has no default, since which provider runs is a
 * deliberate choice {@link ConfigValidator} enforces is actually present.
 */
public record AiProviderConfig(String active, Map<String, ProviderConfig> providers) {

    public AiProviderConfig {
        providers = providers == null ? Map.of() : Map.copyOf(providers);
    }
}
