package com.devmanchego.jtestforge.config;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * {@code spring.mockBeanAnnotation} — jtestforge-specification.md §5, §7.3.
 * {@code AUTO} resolves to {@code @MockitoBean} on Spring Framework 6.2+/Boot 3.4+, and
 * to {@code @MockBean} below that, based on the detected framework version.
 */
public enum MockBeanAnnotation {
    @JsonProperty("auto") AUTO,
    @JsonProperty("mockBean") MOCK_BEAN,
    @JsonProperty("mockitoBean") MOCKITO_BEAN
}
