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

    /**
     * The scaffolded template ships {@code javaVersion:}, {@code dependencyTreeFile:},
     * {@code localRepository:} and {@code javaHome:} with nothing after the colon, so a
     * user sees them and knows they exist without needing to uncomment anything. YAML
     * binds an empty scalar to {@code null}, which every reader of these fields already
     * treats as "not set" - so this must not become the literal string {@code ""}.
     */
    @Test
    void emptyScalarsInTheProjectBlockBindToNullRatherThanAnEmptyString(@TempDir Path dir) throws IOException {
        Path yamlFile = dir.resolve("jtestforge.yaml");
        Files.writeString(yamlFile, """
                project:
                  modulePath: .
                  javaHome:
                  javaVersion:
                  dependencyTreeFile:
                  localRepository:
                """);

        ProjectConfig project = loader.load(yamlFile, Map.of()).config().project();

        assertThat(project.javaHome()).isNull();
        assertThat(project.javaVersion()).isNull();
        assertThat(project.dependencyTreeFile()).isNull();
        assertThat(project.localRepository()).isNull();
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

    @Test
    void sourceEncodingBindsFromTheProjectBlockAndIsNullWhenOmitted(@TempDir Path dir) throws IOException {
        Path withEncoding = dir.resolve("with.yaml");
        Files.writeString(withEncoding, "project:\n  modulePath: .\n  sourceEncoding: ISO-8859-1\n");
        Path without = dir.resolve("without.yaml");
        Files.writeString(without, "project:\n  modulePath: .\n");

        assertThat(loader.load(withEncoding, Map.of()).config().project().sourceEncoding()).isEqualTo("ISO-8859-1");
        assertThat(loader.load(without, Map.of()).config().project().sourceEncoding()).isNull();
    }

    @Test
    void anHttpProviderBindsEveryFieldAndDefaultsItsApiToOllama(@TempDir Path dir) throws IOException {
        Path yaml = dir.resolve("http.yaml");
        Files.writeString(yaml, """
                project:
                  modulePath: .
                aiProvider:
                  active: ollama-http
                  providers:
                    ollama-http:
                      type: http
                      baseUrl: http://localhost:11434
                      model: qwen3-coder:30b
                      keepAlive: 30m
                      options:
                        num_ctx: 32768
                        temperature: 0.7
                      headers:
                        X-Proxy-Token: abc
                """);

        ProviderConfig provider = loader.load(yaml, Map.of()).config().aiProvider().providers().get("ollama-http");

        assertThat(provider.type()).isEqualTo(ProviderType.HTTP);
        assertThat(provider.isHttp()).isTrue();
        assertThat(provider.api()).isEqualTo("ollama");
        assertThat(provider.baseUrl()).isEqualTo("http://localhost:11434");
        assertThat(provider.model()).isEqualTo("qwen3-coder:30b");
        assertThat(provider.keepAlive()).isEqualTo("30m");
        assertThat(provider.options()).containsEntry("num_ctx", 32768).containsEntry("temperature", 0.7);
        assertThat(provider.headers()).containsEntry("X-Proxy-Token", "abc");
        assertThat(provider.command()).isNull();
    }

    @Test
    void aProviderWithoutATypeIsStillAProcessProviderSoExistingConfigsKeepWorking(@TempDir Path dir)
            throws IOException {
        Path yaml = dir.resolve("process.yaml");
        Files.writeString(yaml, """
                project:
                  modulePath: .
                aiProvider:
                  active: claude
                  providers:
                    claude:
                      command: claude
                      args: ["-p"]
                """);

        ProviderConfig provider = loader.load(yaml, Map.of()).config().aiProvider().providers().get("claude");

        assertThat(provider.type()).isEqualTo(ProviderType.PROCESS);
        assertThat(provider.isHttp()).isFalse();
        assertThat(provider.api()).isNull();
        assertThat(provider.options()).isEmpty();
        assertThat(provider.headers()).isEmpty();
        assertThat(provider.command()).isEqualTo("claude");
    }
}
