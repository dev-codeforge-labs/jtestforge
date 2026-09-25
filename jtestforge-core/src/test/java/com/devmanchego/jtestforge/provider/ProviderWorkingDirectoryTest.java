package com.devmanchego.jtestforge.provider;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An agentic CLI explores whatever directory it is started in. Since every piece of
 * context is already in the prompt, that exploration is pure latency - see
 * {@link ProviderWorkingDirectory} for the measurements behind this default.
 */
class ProviderWorkingDirectoryTest {

    @Test
    void isolationCreatesAnEmptyDirectoryUnderTheStateDir(@TempDir Path base) throws IOException {
        Path modulePath = Files.createDirectories(base.resolve("module"));
        Files.writeString(modulePath.resolve("Anything.java"), "class Anything {}");
        Path stateDir = base.resolve(".jtestforge");

        Path resolved = ProviderWorkingDirectory.resolve(true, modulePath, stateDir);

        assertThat(resolved).isDirectory().isNotEqualTo(modulePath);
        assertThat(resolved.getParent()).isEqualTo(stateDir);
        try (var entries = Files.list(resolved)) {
            assertThat(entries).isEmpty();
        }
    }

    @Test
    void isolationIsIdempotentAcrossRuns(@TempDir Path base) throws IOException {
        Path stateDir = base.resolve(".jtestforge");

        Path first = ProviderWorkingDirectory.resolve(true, base.resolve("module"), stateDir);
        Path second = ProviderWorkingDirectory.resolve(true, base.resolve("module"), stateDir);

        assertThat(first).isEqualTo(second).isDirectory();
    }

    @Test
    void withIsolationOffTheModuleDirectoryIsUsedUnchanged(@TempDir Path base) throws IOException {
        Path modulePath = base.resolve("module");
        Path stateDir = base.resolve(".jtestforge");

        Path resolved = ProviderWorkingDirectory.resolve(false, modulePath, stateDir);

        assertThat(resolved).isEqualTo(modulePath);
        assertThat(stateDir).doesNotExist();
    }
}
