package com.devmanchego.jtestforge.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Spec;

/**
 * Root command. Exit codes follow jtestforge-specification.md §14.1; they are wired in
 * as each subcommand's engine is implemented (see jtestforge-implementation-plan.md).
 */
@Command(
        name = "jtestforge",
        mixinStandardHelpOptions = true,
        versionProvider = ManifestVersionProvider.class,
        description = "Raises JUnit 5 test quality (Mockito unit and Spring slice tests) "
                + "in a Maven module by driving an external AI CLI in a verified loop.",
        subcommands = {
                InitCommand.class,
                ScanCommand.class,
                GenerateCommand.class,
                HardenCommand.class,
                StatusCommand.class,
                ReportCommand.class,
                CleanCommand.class
        }
)
public final class JTestForgeCommand implements Runnable {

    @Spec
    private CommandLine.Model.CommandSpec spec;

    @Override
    public void run() {
        // Invoked when jtestforge is run with no subcommand: show usage rather than
        // exiting silently. Writes through the invoking CommandLine's configured
        // output stream (spec.commandLine().getOut()) rather than System.out directly,
        // so tests can capture it by calling CommandLine#setOut.
        spec.commandLine().usage(spec.commandLine().getOut());
    }

    public static void main(String[] args) {
        ConsoleOutput console = new ConsoleOutput();
        CommandLine commandLine = new CommandLine(new JTestForgeCommand());
        commandLine.setExecutionExceptionHandler((ex, cl, parseResult) -> {
            if (ex instanceof NotYetImplementedException) {
                console.error(ex.getMessage());
                return 1;
            }
            console.error("Unexpected error: " + ex.getMessage());
            return 1;
        });
        int exitCode = commandLine.execute(args);
        System.exit(exitCode);
    }
}
