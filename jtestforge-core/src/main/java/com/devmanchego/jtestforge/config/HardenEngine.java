package com.devmanchego.jtestforge.config;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * {@code harden.engine} — jtestforge-specification.md §13.2. Selects the
 * {@code MutationRunner} implementation: the {@code pitest-maven} plugin, or the
 * external standalone wrapper fat jar used where {@code org.pitest} artifacts are
 * blocked in the internal Maven repository.
 */
public enum HardenEngine {
    @JsonProperty("pitestMavenPlugin") PITEST_MAVEN_PLUGIN,
    @JsonProperty("standaloneWrapper") STANDALONE_WRAPPER
}
