package com.devmanchego.jtestforge.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ExecutableResolverTest {

    @Test
    void anAbsolutePathToAnExistingFileResolvesToItself(@TempDir Path dir) throws Exception {
        Path executable = Files.createFile(dir.resolve("mytool.exe"));

        assertThat(ExecutableResolver.resolve(executable.toString(), List.of())).contains(executable);
    }

    @Test
    void anAbsolutePathToAMissingFileDoesNotResolve(@TempDir Path dir) {
        Path missing = dir.resolve("does-not-exist.exe");

        assertThat(ExecutableResolver.resolve(missing.toString(), List.of())).isEmpty();
    }

    @Test
    void aBareCommandNameResolvesToTheFirstMatchingExtensionOnPath(@TempDir Path pathDir) throws Exception {
        // On Windows a bare "mvn" cannot be launched directly by ProcessBuilder even
        // when "mvn.cmd" is genuinely on PATH - resolve() must return the extended name.
        Path script = Files.createFile(pathDir.resolve("mvn.cmd"));

        Optional<Path> resolved = ExecutableResolver.resolve("mvn", List.of(pathDir.toString()));

        assertThat(resolved).contains(script);
    }

    @Test
    void aBareCommandNameNotOnAnyPathDirectoryDoesNotResolve(@TempDir Path pathDir) {
        assertThat(ExecutableResolver.resolve("nope", List.of(pathDir.toString()))).isEmpty();
    }

    @Test
    void aBlankOrNullCommandNeverResolves() {
        assertThat(ExecutableResolver.resolve("", List.of())).isEmpty();
        assertThat(ExecutableResolver.resolve(null, List.of())).isEmpty();
    }

    @Test
    void isResolvableIsConsistentWithResolve(@TempDir Path pathDir) throws Exception {
        Files.createFile(pathDir.resolve("claude.cmd"));

        assertThat(ExecutableResolver.isResolvable("claude", List.of(pathDir.toString()))).isTrue();
        assertThat(ExecutableResolver.isResolvable("nope", List.of(pathDir.toString()))).isFalse();
    }

    @Test
    void systemPathDirectoriesIsNonEmptyInAnyRealEnvironment() {
        assertThat(ExecutableResolver.systemPathDirectories()).isNotEmpty();
    }
}
