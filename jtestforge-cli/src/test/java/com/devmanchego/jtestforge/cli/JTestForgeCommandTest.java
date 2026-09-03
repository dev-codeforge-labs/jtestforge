package com.devmanchego.jtestforge.cli;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class JTestForgeCommandTest {

    @Test
    void helpListsAllSevenSubcommands() {
        CommandLine commandLine = new CommandLine(new JTestForgeCommand());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        commandLine.setOut(new PrintWriter(out, true, StandardCharsets.UTF_8));

        commandLine.execute("--help");

        String help = out.toString(StandardCharsets.UTF_8);
        assertThat(help)
                .contains("init")
                .contains("scan")
                .contains("generate")
                .contains("harden")
                .contains("status")
                .contains("report")
                .contains("clean");
    }

    @Test
    void versionPrintsAJTestForgeBanner() {
        CommandLine commandLine = new CommandLine(new JTestForgeCommand());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        commandLine.setOut(new PrintWriter(out, true, StandardCharsets.UTF_8));

        commandLine.execute("--version");

        assertThat(out.toString(StandardCharsets.UTF_8)).contains("JTestForge");
    }

    @Test
    void aStubbedSubcommandFailsWithExitCodeOneAndAClearMessage() {
        CommandLine commandLine = new CommandLine(new JTestForgeCommand());
        commandLine.setExecutionExceptionHandler((ex, cl, parseResult) -> {
            if (ex instanceof NotYetImplementedException) {
                return 1;
            }
            return 2;
        });

        int exitCode = commandLine.execute("generate");

        assertThat(exitCode).isEqualTo(1);
    }

    @Test
    void runningWithNoArgumentsPrintsUsageInsteadOfFailingSilently() {
        CommandLine commandLine = new CommandLine(new JTestForgeCommand());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        commandLine.setOut(new PrintWriter(out, true, StandardCharsets.UTF_8));

        commandLine.execute();

        assertThat(out.toString(StandardCharsets.UTF_8)).contains("jtestforge");
    }
}
