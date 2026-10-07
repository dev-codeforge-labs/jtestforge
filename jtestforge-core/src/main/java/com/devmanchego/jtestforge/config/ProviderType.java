package com.devmanchego.jtestforge.config;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * {@code aiProvider.providers.*.type}: how JTestForge talks to the model.
 *
 * <p>{@link #PROCESS} (the default, and the only shape before HTTP providers existed) launches an
 * AI CLI as a child process; {@link #HTTP} calls a model server over HTTP. Defaulting to
 * {@code PROCESS} is what keeps every existing {@code jtestforge.yaml} valid unchanged.
 */
public enum ProviderType {
    @JsonProperty("process") PROCESS,
    @JsonProperty("http") HTTP
}
