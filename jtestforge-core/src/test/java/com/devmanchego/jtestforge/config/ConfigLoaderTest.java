package com.devmanchego.jtestforge.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfigLoaderTest {

    private final ConfigLoader loader = new ConfigLoader();

    @Test
    void aValidConfigBindsFullyWithEnvVarsExpandedAndDefaultsApplied() throws URISyntaxException {
        Path yamlFile = classpathResource("config/valid-jtestforge.yaml");
        Map<String, String> environment = Map.of(
                "JTF_TEST_MODULE_PATH", "C:/work/app/core",
                "JTF_TEST_COMMAND", "claude");

        ConfigLoadResult result = loader.load(yamlFile, environment);

        assertThat(result.config().project().modulePath()).isEqualTo("C:/work/app/core");
        assertThat(result.config().aiProvider().providers().get("claude").command()).isEqualTo("claude");
        // Untouched blocks fall back to their full defaults.
        assertThat(result.config().execution().backupOriginalTests()).isTrue();
        assertThat(result.config().harden().maxTierForMutation())
                .isEqualTo(com.devmanchego.jtestforge.model.Tier.PLAIN_UNIT);
        assertThat(result.config().selection().minComplexity()).isEqualTo(3);
        assertThat(result.warnings()).isEmpty();
        assertThat(result.configHash()).startsWith("sha256:");
    }

    @Test
    void unknownTopLevelKeysProduceAWarningRatherThanAFailure(@TempDir Path dir) throws IOException {
        Path yamlFile = dir.resolve("jtestforge.yaml");
        Files.writeString(yamlFile, """
                project:
                  modulePath: C:/work/app/core
                totallyUnknownSection:
                  foo: bar
                """);

        ConfigLoadResult result = loader.load(yamlFile, Map.of());

        assertThat(result.config().project().modulePath()).isEqualTo("C:/work/app/core");
        assertThat(result.warnings())
                .extracting(ConfigViolation::path)
                .containsExactly("totallyUnknownSection");
    }

    @Test
    void aMissingConfigFileFailsWithAClearMessage(@TempDir Path dir) {
        Path missing = dir.resolve("does-not-exist.yaml");

        assertThatThrownBy(() -> loader.load(missing, Map.of()))
                .isInstanceOf(ConfigLoadException.class);
    }

    @Test
    void malformedYamlFailsWithAClearMessage(@TempDir Path dir) throws IOException {
        Path yamlFile = dir.resolve("jtestforge.yaml");
        Files.writeString(yamlFile, "project: [this is not a map");

        assertThatThrownBy(() -> loader.load(yamlFile, Map.of()))
                .isInstanceOf(ConfigLoadException.class);
    }

    @Test
    void omittingAnEntireBlockYieldsTheSameResultAsWritingAllItsDefaults(@TempDir Path dir) throws IOException {
        Path withoutSpringBlock = dir.resolve("a.yaml");
        Files.writeString(withoutSpringBlock, "project:\n  modulePath: C:/x\n");

        ConfigLoadResult result = loader.load(withoutSpringBlock, Map.of());

        assertThat(result.config().spring().enabled()).isEqualTo(SpringEnabledMode.AUTO);
        assertThat(result.config().spring().tiers().webSlice()).isTrue();
        assertThat(result.config().spring().tiers().contextSlice()).isFalse();
    }

    private Path classpathResource(String resourcePath) throws URISyntaxException {
        return Path.of(getClass().getClassLoader().getResource(resourcePath).toURI());
    }
}
