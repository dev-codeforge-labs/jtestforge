package com.devmanchego.jtestforge.config;

import com.fasterxml.jackson.annotation.JsonProperty;

/** {@code spring.embeddedDatabase} — jtestforge-specification.md §5. */
public enum EmbeddedDatabase {
    @JsonProperty("auto") AUTO,
    @JsonProperty("h2") H2,
    @JsonProperty("hsqldb") HSQLDB,
    @JsonProperty("derby") DERBY,
    @JsonProperty("none") NONE
}
