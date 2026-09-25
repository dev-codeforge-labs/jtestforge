package com.devmanchego.jtestforge.cli;

import com.devmanchego.jtestforge.config.JTestForgeConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Command-line overrides beat whatever the config file itself says - not just its absence.
 * A user should not have to blank out {@code jtestforge.yaml} to try a different value on
 * one invocation.
 */
class ConfigResolverTest {

    @Test
    void commandLineOverridesWinOverValuesAlreadySetInTheFile(@TempDir Path dir) throws IOException {
        Path fileTree = dir.resolve("from-file-tree.txt");
        Path fileRepo = dir.resolve("from-file-repo");
        Path cliTree = dir.resolve("from-cli-tree.txt");
        Path cliRepo = dir.resolve("from-cli-repo");
        Path cliJavaHome = dir.resolve("from-cli-jdk");
        Files.writeString(fileTree, "");
        Files.createDirectories(fileRepo);
        Files.writeString(cliTree, "");
        Files.createDirectories(cliRepo);
        Files.createDirectories(cliJavaHome);
        Files.writeString(dir.resolve("jtestforge.yaml"), """
                project:
                  modulePath: .
                  javaHome: %s
                  javaVersion: 11
                  dependencyTreeFile: %s
                  localRepository: %s
                """.formatted(dir.resolve("from-file-jdk"), fileTree, fileRepo), StandardCharsets.UTF_8);

        CommonModuleOptions options = new CommonModuleOptions();
        options.module = dir;
        options.dependencyTree = cliTree;
        options.localRepository = cliRepo;
        options.javaVersion = "8";
        options.javaHome = cliJavaHome;

        JTestForgeConfig config = ConfigResolver.load(options).config();

        assertThat(config.project().javaHome()).isEqualTo(cliJavaHome.toAbsolutePath().toString());
        assertThat(config.project().javaVersion()).isEqualTo("8");
        assertThat(config.project().dependencyTreeFile()).isEqualTo(cliTree.toAbsolutePath().toString());
        assertThat(config.project().localRepository()).isEqualTo(cliRepo.toAbsolutePath().toString());
    }

    @Test
    void withoutCommandLineOverridesTheFilesOwnValuesAreKept(@TempDir Path dir) throws IOException {
        Path fileTree = dir.resolve("from-file-tree.txt");
        Files.writeString(fileTree, "");
        Files.writeString(dir.resolve("jtestforge.yaml"), """
                project:
                  modulePath: .
                  javaVersion: 11
                  dependencyTreeFile: %s
                """.formatted(fileTree), StandardCharsets.UTF_8);

        CommonModuleOptions options = new CommonModuleOptions();
        options.module = dir;

        JTestForgeConfig config = ConfigResolver.load(options).config();

        assertThat(config.project().javaVersion()).isEqualTo("11");
        assertThat(config.project().dependencyTreeFile()).isEqualTo(fileTree.toString());
    }
}
