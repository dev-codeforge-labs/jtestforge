package com.devmanchego.jtestforge.cli;

import com.devmanchego.jtestforge.config.ConfigLoadResult;
import com.devmanchego.jtestforge.config.ConfigLoader;
import com.devmanchego.jtestforge.config.JTestForgeConfig;
import com.devmanchego.jtestforge.config.ProjectConfig;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Locates and loads {@code jtestforge.yaml} — jtestforge-specification.md §5's resolution
 * order: {@code --config <path>} → {@code ./jtestforge.yaml} → {@code <modulePath>/jtestforge.yaml}.
 */
final class ConfigResolver {

    private ConfigResolver() {
    }

    /** The path the config would be read from, without requiring it to exist yet. */
    static Path resolvePath(CommonModuleOptions options) {
        if (options.config != null) {
            return options.config;
        }
        Path cwdCandidate = Path.of("jtestforge.yaml");
        if (Files.isRegularFile(cwdCandidate)) {
            return cwdCandidate;
        }
        if (options.module != null) {
            return options.module.resolve("jtestforge.yaml");
        }
        return cwdCandidate;
    }

    /**
     * Loads the config at {@link #resolvePath}, applying {@code --module},
     * {@code --dependency-tree}, {@code --local-repository}, {@code --java-version} and
     * {@code --java-home} as overrides of the {@code project} block - after loading, since
     * they are per-invocation choices, not part of the file's own content or its hash.
     *
     * @throws com.devmanchego.jtestforge.config.ConfigLoadException if the file does not
     *         exist or cannot be parsed
     */
    static ConfigLoadResult load(CommonModuleOptions options) {
        Path path = resolvePath(options);
        ConfigLoadResult result = new ConfigLoader().load(path, System.getenv());
        if (!options.overridesProject()) {
            return result;
        }
        JTestForgeConfig withOverrides = withProjectOverrides(result.config(), options);
        return new ConfigLoadResult(withOverrides, result.configHash(), result.sourceFile(), result.warnings());
    }

    /** The module path a loaded config resolves to, as an absolute directory. */
    static Path modulePathOf(JTestForgeConfig config) {
        return Path.of(config.project().modulePath()).toAbsolutePath().normalize();
    }

    /** Relative paths on the command line are relative to the working directory, not the config file. */
    private static JTestForgeConfig withProjectOverrides(JTestForgeConfig config, CommonModuleOptions options) {
        ProjectConfig project = config.project();
        ProjectConfig overridden = new ProjectConfig(
                options.module != null ? options.module.toString() : project.modulePath(),
                project.mavenExecutable(), project.mavenArgs(),
                options.javaHome != null ? options.javaHome.toAbsolutePath().toString() : project.javaHome(),
                project.testSourceRoot(), project.mainSourceRoot(),
                project.testClassSuffix(), project.testClassSuffixByTier(),
                options.dependencyTree != null
                        ? options.dependencyTree.toAbsolutePath().toString() : project.dependencyTreeFile(),
                options.localRepository != null
                        ? options.localRepository.toAbsolutePath().toString() : project.localRepository(),
                options.javaVersion != null ? options.javaVersion : project.javaVersion());
        return new JTestForgeConfig(overridden, config.selection(), config.aiProvider(), config.prompts(),
                config.spring(), config.context(), config.generate(), config.harden(), config.execution());
    }

    /** Resolves {@code execution.stateDir} against the module path, per §5. */
    static Path stateDirOf(JTestForgeConfig config, Path modulePath) {
        Path stateDir = Path.of(config.execution().stateDir());
        return stateDir.isAbsolute() ? stateDir : modulePath.resolve(stateDir);
    }
}
