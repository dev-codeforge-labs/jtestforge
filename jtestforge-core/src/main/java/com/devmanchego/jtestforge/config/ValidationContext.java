package com.devmanchego.jtestforge.config;

import com.devmanchego.jtestforge.util.ExecutableResolver;

import java.nio.file.Path;
import java.util.List;

/**
 * Facts about the surrounding environment that {@link ConfigValidator} needs but cannot
 * determine from the config file's own text: where relative paths (prompt templates,
 * the standalone wrapper jar) resolve against, what directories PATH search covers, and
 * two facts genuinely owned by later phases:
 *
 * <ul>
 *   <li>{@code springTestOnClasspath} - real detection is
 *       {@code SpringStackDetector} (§7.3, implementation phase 4), which needs the
 *       module's resolved Maven classpath. Until that exists, callers pass a computed
 *       value or accept the safe default of {@code false}.</li>
 *   <li>{@code dockerReachable} - real detection needs a Docker socket probe
 *       (implementation phase 6). Same treatment.</li>
 * </ul>
 */
public record ValidationContext(
        Path configBaseDir,
        List<String> pathDirectories,
        boolean springTestOnClasspath,
        boolean dockerReachable) {

    public ValidationContext {
        pathDirectories = pathDirectories == null ? List.of() : List.copyOf(pathDirectories);
    }

    /**
     * Resolves paths relative to {@code configFile}'s parent directory, and reads the
     * real process PATH. {@code springTestOnClasspath} and {@code dockerReachable}
     * default to {@code false} - the safe, non-permissive default until the phases that
     * own their real detection exist.
     */
    public static ValidationContext forConfigFile(Path configFile) {
        Path baseDir = configFile.toAbsolutePath().getParent();
        return new ValidationContext(baseDir, ExecutableResolver.systemPathDirectories(), false, false);
    }
}
