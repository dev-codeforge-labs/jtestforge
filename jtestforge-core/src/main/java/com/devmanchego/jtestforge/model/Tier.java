package com.devmanchego.jtestforge.model;

/**
 * The cheapest kind of test that can close a given work unit's gap, per
 * jtestforge-specification.md §7.4. Ordered from cheapest to most expensive; that
 * ordinal order is relied on by {@code selection.order} (cheapest tier first) and by
 * {@code harden.maxTierForMutation} range checks.
 */
public enum Tier {
    /** JUnit 5 + Mockito, no Spring context. */
    PLAIN_UNIT,
    /** {@code @WebMvcTest} + MockMvc/MockMvcTester. */
    WEB_SLICE,
    /** {@code @DataJpaTest} + embedded database. */
    DATA_SLICE,
    /** {@code @JsonTest} / {@code @ConfigurationProperties} binding. */
    JSON_SLICE,
    /** Minimal {@code @SpringBootTest(classes = ...)}. Disabled by default. */
    CONTEXT_SLICE
}
