package com.devmanchego.jtestforge.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ConfigHasherTest {

    @Test
    void hashHasTheSha256PrefixFormatUsedInStateJson() {
        String hash = ConfigHasher.hash(minimalConfig("C:/work/app/core"));

        assertThat(hash).startsWith("sha256:");
        assertThat(hash).hasSize("sha256:".length() + 64);
    }

    @Test
    void identicalConfigsProduceTheSameHash() {
        String first = ConfigHasher.hash(minimalConfig("C:/work/app/core"));
        String second = ConfigHasher.hash(minimalConfig("C:/work/app/core"));

        assertThat(first).isEqualTo(second);
    }

    @Test
    void aSemanticDifferenceProducesADifferentHash() {
        String first = ConfigHasher.hash(minimalConfig("C:/work/app/core"));
        String second = ConfigHasher.hash(minimalConfig("C:/work/app/different-module"));

        assertThat(first).isNotEqualTo(second);
    }

    private JTestForgeConfig minimalConfig(String modulePath) {
        return new JTestForgeConfig(
                new ProjectConfig(modulePath, null, null, null, null, null, null, null),
                null, null, null, null, null, null, null, null);
    }
}
