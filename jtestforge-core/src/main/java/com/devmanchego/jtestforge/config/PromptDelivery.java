package com.devmanchego.jtestforge.config;

import com.fasterxml.jackson.annotation.JsonProperty;

/** {@code aiProvider.providers.*.promptDelivery} — jtestforge-specification.md §5, §12.2. */
public enum PromptDelivery {
    @JsonProperty("stdin") STDIN,
    @JsonProperty("argument") ARGUMENT,
    @JsonProperty("file") FILE
}
