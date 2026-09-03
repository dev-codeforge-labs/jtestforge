package com.devmanchego.jtestforge.cli;

import com.devmanchego.jtestforge.config.ConfigLoadResult;
import com.devmanchego.jtestforge.config.ConfigLoader;
import com.devmanchego.jtestforge.config.JTestForgeConfig;
import com.devmanchego.jtestforge.config.ProjectConfig;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

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
     * Loads the config at {@link #resolvePath}, applying {@code --module} as an override
     * of {@code project.modulePath} - the override happens after loading, since it is a
     * per-invocation choice, not part of the file's own content or its hash.
     *
     * @throws com.devmanchego.jtestforge.config.ConfigLoadException if the file does not
     *         exist or cannot be parsed
     */
    static ConfigLoadResult load(CommonModuleOptions options) {
        Path path = resolvePath(options);
        ConfigLoadResult result = new ConfigLoader().load(path, System.getenv());
        if (options.module == null) {
            return result;
        }
        JTestForgeConfig withOverride = withModulePath(result.config(), options.module.toString());
        return new ConfigLoadResult(withOverride, result.configHash(), result.sourceFile(), result.warnings());
    }

    /** The module path a loaded config resolves to, as an absolute directory. */
    static Path modulePathOf(JTestForgeConfig config) {
        return Path.of(config.project().modulePath()).toAbsolutePath().normalize();
    }

    private static JTestForgeConfig withModulePath(JTestForgeConfig config, String modulePath) {
        ProjectConfig project = config.project();
        ProjectConfig overridden = new ProjectConfig(modulePath, project.mavenExecutable(), project.mavenArgs(),
                project.javaHome(), project.testSourceRoot(), project.mainSourceRoot(),
                project.testClassSuffix(), project.testClassSuffixByTier());
        return new JTestForgeConfig(overridden, config.selection(), config.aiProvider(), config.prompts(),
                config.spring(), config.context(), config.generate(), config.harden(), config.execution());
    }

    /** Resolves {@code execution.stateDir} against the module path, per §5. */
    static Path stateDirOf(JTestForgeConfig config, Path modulePath) {
        Path stateDir = Path.of(config.execution().stateDir());
        return stateDir.isAbsolute() ? stateDir : modulePath.resolve(stateDir);
    }
}
