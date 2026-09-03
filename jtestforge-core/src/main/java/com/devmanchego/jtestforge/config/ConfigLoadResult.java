package com.devmanchego.jtestforge.config;

import java.nio.file.Path;
import java.util.List;

/**
 * Result of {@link ConfigLoader#load}: the bound, fully-defaulted config, its content
 * hash (jtestforge-specification.md §8), the file it came from, and any non-blocking
 * warnings found while binding (currently: unknown top-level keys, §5.1).
 *
 * <p>Blocking violations are a separate concern, produced by {@link ConfigValidator}
 * against the bound config - loading and semantic validation are deliberately two
 * passes, so a syntactically valid but semantically wrong config still binds to a
 * complete, inspectable object instead of failing before any of its content can be
 * reported at once.
 */
public record ConfigLoadResult(
        JTestForgeConfig config,
        String configHash,
        Path sourceFile,
        List<ConfigViolation> warnings) {

    public ConfigLoadResult {
        warnings = List.copyOf(warnings);
    }
}
