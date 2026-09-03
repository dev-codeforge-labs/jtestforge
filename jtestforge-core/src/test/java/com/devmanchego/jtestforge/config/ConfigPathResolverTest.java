package com.devmanchego.jtestforge.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ConfigPathResolverTest {

    private final ConfigPathResolver resolver = new ConfigPathResolver();

    @Test
    void anExplicitConfigPathWinsOverEverythingElse(@TempDir Path cwd) throws IOException {
        Path explicit = cwd.resolve("custom.yaml");
        Files.writeString(cwd.resolve("jtestforge.yaml"), "project: {}");

        Optional<Path> resolved = resolver.resolve(Optional.of(explicit), cwd, Optional.empty());

        assertThat(resolved).contains(explicit);
    }

    @Test
    void explicitConfigPathIsReturnedEvenIfItDoesNotExist(@TempDir Path cwd) {
        Path explicit = cwd.resolve("missing.yaml");

        Optional<Path> resolved = resolver.resolve(Optional.of(explicit), cwd, Optional.empty());

        assertThat(resolved).contains(explicit);
    }

    @Test
    void fallsBackToJtestforgeYamlInTheCurrentWorkingDirectory(@TempDir Path cwd) throws IOException {
        Path inCwd = cwd.resolve("jtestforge.yaml");
        Files.writeString(inCwd, "project: {}");

        Optional<Path> resolved = resolver.resolve(Optional.empty(), cwd, Optional.empty());

        assertThat(resolved).contains(inCwd);
    }

    @Test
    void fallsBackToTheModuleDirectoryWhenNothingIsInTheCurrentWorkingDirectory(
            @TempDir Path cwd, @TempDir Path module) throws IOException {
        Path inModule = module.resolve("jtestforge.yaml");
        Files.writeString(inModule, "project: {}");

        Optional<Path> resolved = resolver.resolve(Optional.empty(), cwd, Optional.of(module));

        assertThat(resolved).contains(inModule);
    }

    @Test
    void returnsEmptyWhenNoneOfTheThreeLocationsHasAFile(@TempDir Path cwd, @TempDir Path module) {
        Optional<Path> resolved = resolver.resolve(Optional.empty(), cwd, Optional.of(module));

        assertThat(resolved).isEmpty();
    }
}
