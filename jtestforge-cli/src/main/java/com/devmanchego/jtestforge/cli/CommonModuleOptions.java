package com.devmanchego.jtestforge.cli;

import picocli.CommandLine.Option;

import java.nio.file.Path;

/**
 * The options every module-scoped subcommand shares — jtestforge-specification.md §14's
 * "Common options" table, the subset relevant to a command that does not invoke an AI
 * provider ({@code init}, {@code scan}, {@code status}, {@code clean}).
 */
final class CommonModuleOptions {

    @Option(names = {"-c", "--config"}, description = "Config file. Default: ./jtestforge.yaml")
    Path config;

    @Option(names = {"-m", "--module"}, description = "Override project.modulePath")
    Path module;

    @Option(names = {"-v", "--verbose"}, description = "Debug logging")
    boolean verbose;
}
