package com.devmanchego.jtestforge.config;

import com.devmanchego.jtestforge.model.Tier;

/**
 * {@code spring.tiers} block — jtestforge-specification.md §5, §7.4. All tiers default
 * to enabled except {@code contextSlice} (T4), which is opt-in per §1.1.
 */
public record TiersConfig(
        Boolean plainUnit,
        Boolean webSlice,
        Boolean dataSlice,
        Boolean jsonSlice,
        Boolean contextSlice) {

    public TiersConfig {
        plainUnit = plainUnit == null ? Boolean.TRUE : plainUnit;
        webSlice = webSlice == null ? Boolean.TRUE : webSlice;
        dataSlice = dataSlice == null ? Boolean.TRUE : dataSlice;
        jsonSlice = jsonSlice == null ? Boolean.TRUE : jsonSlice;
        contextSlice = contextSlice == null ? Boolean.FALSE : contextSlice;
    }

    /** Whether {@code tier} is enabled by this block. {@code PLAIN_UNIT} is always true. */
    public boolean isEnabled(Tier tier) {
        return switch (tier) {
            case PLAIN_UNIT -> plainUnit;
            case WEB_SLICE -> webSlice;
            case DATA_SLICE -> dataSlice;
            case JSON_SLICE -> jsonSlice;
            case CONTEXT_SLICE -> contextSlice;
        };
    }
}
