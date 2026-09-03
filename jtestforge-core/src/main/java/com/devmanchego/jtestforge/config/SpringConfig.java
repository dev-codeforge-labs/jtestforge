package com.devmanchego.jtestforge.config;

/** {@code spring} block — jtestforge-specification.md §5, §2, §7.3-§7.6. */
public record SpringConfig(
        SpringEnabledMode enabled,
        TiersConfig tiers,
        Boolean preferLowestTier,
        Boolean detectFrameworkSemanticGaps,
        EmbeddedDatabase embeddedDatabase,
        Boolean allowTestcontainers,
        Integer maxContextLoadsPerRun,
        Integer contextLoadTimeoutSeconds,
        Boolean enforceContextKeyStability,
        Boolean forbidDirtiesContext,
        MockBeanAnnotation mockBeanAnnotation) {

    public SpringConfig {
        enabled = enabled == null ? SpringEnabledMode.AUTO : enabled;
        tiers = tiers == null ? new TiersConfig(null, null, null, null, null) : tiers;
        preferLowestTier = preferLowestTier == null ? Boolean.TRUE : preferLowestTier;
        detectFrameworkSemanticGaps = detectFrameworkSemanticGaps == null ? Boolean.TRUE : detectFrameworkSemanticGaps;
        embeddedDatabase = embeddedDatabase == null ? EmbeddedDatabase.AUTO : embeddedDatabase;
        allowTestcontainers = allowTestcontainers == null ? Boolean.FALSE : allowTestcontainers;
        maxContextLoadsPerRun = maxContextLoadsPerRun == null ? 40 : maxContextLoadsPerRun;
        contextLoadTimeoutSeconds = contextLoadTimeoutSeconds == null ? 120 : contextLoadTimeoutSeconds;
        enforceContextKeyStability = enforceContextKeyStability == null ? Boolean.TRUE : enforceContextKeyStability;
        forbidDirtiesContext = forbidDirtiesContext == null ? Boolean.TRUE : forbidDirtiesContext;
        mockBeanAnnotation = mockBeanAnnotation == null ? MockBeanAnnotation.AUTO : mockBeanAnnotation;
    }
}
