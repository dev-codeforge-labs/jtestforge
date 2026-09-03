package com.devmanchego.jtestforge.config;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

/**
 * {@code spring.enabled} — jtestforge-specification.md §5, §5.1. A tri-state: the YAML
 * value is either the literal boolean {@code true}/{@code false}, or the string
 * {@code "auto"} (enable only when {@code spring-test} is detected on the test
 * classpath). See {@link SpringEnabledModeDeserializer} for how the mixed boolean/string
 * YAML shape is handled.
 */
@JsonDeserialize(using = SpringEnabledModeDeserializer.class)
public enum SpringEnabledMode {
    AUTO,
    ENABLED,
    DISABLED
}
