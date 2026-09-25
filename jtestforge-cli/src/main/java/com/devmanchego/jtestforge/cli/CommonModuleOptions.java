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

    @Option(names = {"-v", "--verbose"}, description = "Debug logging. Echoes every external command "
            + "(Maven, the AI provider CLI) to the console as it runs, and on generate, also every AI "
            + "provider request and response (or failure, with its full stdout/stderr)")
    boolean verbose;

    @Option(names = "--dependency-tree", description = "Saved 'mvn dependency:tree' output (may cover the "
            + "whole multi-module application) used instead of running Maven to resolve dependencies. "
            + "Overrides project.dependencyTreeFile")
    Path dependencyTree;

    @Option(names = "--local-repository", description = "Local Maven repository holding the tree's jars. "
            + "Overrides project.localRepository. Default: detected from Maven's settings.xml")
    Path localRepository;

    @Option(names = "--java-version", description = "Java release the module's code is written for "
            + "(8, 1.8, 11, 17...). Overrides project.javaVersion. Default: detected from the pom or bytecode")
    String javaVersion;

    @Option(names = "--java-home", description = "JDK every Maven call runs on, exported as JAVA_HOME. "
            + "Overrides project.javaHome")
    Path javaHome;

    boolean overridesProject() {
        return module != null || dependencyTree != null || localRepository != null || javaVersion != null
                || javaHome != null;
    }
}
