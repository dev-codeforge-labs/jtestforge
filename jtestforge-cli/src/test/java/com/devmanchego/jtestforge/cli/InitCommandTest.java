package com.devmanchego.jtestforge.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code jtestforge init} — jtestforge-implementation-plan.md phase 17: scaffolds a config
 * and the eleven bundled prompt/rules files, never overwriting anything already there.
 */
class InitCommandTest {

    @Test
    void aFreshDirectoryGetsAConfigAndElevenPromptFiles(@TempDir Path dir) {
        int exitCode = run(dir);

        assertThat(exitCode).isEqualTo(0);
        Path config = dir.resolve("jtestforge.yaml");
        assertThat(config).exists();
        assertThat(readSafely(config)).contains("project:").contains("modulePath");

        Path prompts = dir.resolve("prompts");
        assertThat(prompts.resolve("new-test-class.md")).exists();
        assertThat(prompts.resolve("additional-tests.md")).exists();
        assertThat(prompts.resolve("kill-mutants.md")).exists();
        assertThat(prompts.resolve("fix-compilation.md")).exists();
        assertThat(prompts.resolve("fix-assertion.md")).exists();
        assertThat(prompts.resolve("spring-web-slice.md")).exists();
        assertThat(prompts.resolve("spring-data-slice.md")).exists();
        assertThat(prompts.resolve("spring-json-slice.md")).exists();
        assertThat(prompts.resolve("spring-context.md")).exists();
        assertThat(prompts.resolve("rules.md")).exists();
        assertThat(prompts.resolve("spring-rules.md")).exists();
    }

    @Test
    void runningInitTwiceDoesNotOverwriteAnEditedConfig(@TempDir Path dir) throws IOException {
        run(dir);
        Path config = dir.resolve("jtestforge.yaml");
        String edited = "project:\n  modulePath: /my/hand/edited/path\n";
        Files.writeString(config, edited, StandardCharsets.UTF_8);

        int secondExitCode = run(dir);

        assertThat(secondExitCode).isEqualTo(0);
        assertThat(readSafely(config)).isEqualTo(edited);
    }

    @Test
    void runningInitTwiceDoesNotOverwriteAnEditedPromptTemplate(@TempDir Path dir) throws IOException {
        run(dir);
        Path rules = dir.resolve("prompts").resolve("rules.md");
        String edited = "# My own house style\nNever use var.\n";
        Files.writeString(rules, edited, StandardCharsets.UTF_8);

        run(dir);

        assertThat(Files.readString(rules)).isEqualTo(edited);
    }

    private int run(Path dir) {
        CommandLine commandLine = new CommandLine(new InitCommand());
        return commandLine.execute("--module", dir.toString());
    }

    private String readSafely(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
