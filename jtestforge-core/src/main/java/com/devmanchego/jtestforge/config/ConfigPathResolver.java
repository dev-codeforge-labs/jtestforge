package com.devmanchego.jtestforge.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Resolves which {@code jtestforge.yaml} to load, per jtestforge-specification.md §5:
 * {@code --config <path>} → {@code ./jtestforge.yaml} → {@code <modulePath>/jtestforge.yaml}.
 *
 * <p>{@code modulePath} here is the CLI's {@code --module} override, not
 * {@code project.modulePath} read from a config that has not been loaded yet — there is
 * no circularity. If none of the three locations yields a file, {@link #resolve} returns
 * {@link Optional#empty()}; the caller decides how to react (typically: suggest
 * {@code jtestforge init}).
 */
public final class ConfigPathResolver {

    private static final String CONFIG_FILE_NAME = "jtestforge.yaml";

    /**
     * @param explicitConfigPath the {@code --config} option, if given. Returned as-is,
     *                           without checking existence: an explicit choice is
     *                           reported as a load error by the caller if missing,
     *                           rather than silently falling through to another location.
     * @param currentWorkingDirectory where {@code ./jtestforge.yaml} is looked up
     * @param moduleOverridePath the CLI's {@code --module} option, if given
     */
    public Optional<Path> resolve(
            Optional<Path> explicitConfigPath,
            Path currentWorkingDirectory,
            Optional<Path> moduleOverridePath) {

        if (explicitConfigPath.isPresent()) {
            return explicitConfigPath;
        }

        Path inWorkingDirectory = currentWorkingDirectory.resolve(CONFIG_FILE_NAME);
        if (Files.isRegularFile(inWorkingDirectory)) {
            return Optional.of(inWorkingDirectory);
        }

        if (moduleOverridePath.isPresent()) {
            Path inModule = moduleOverridePath.get().resolve(CONFIG_FILE_NAME);
            if (Files.isRegularFile(inModule)) {
                return Optional.of(inModule);
            }
        }

        return Optional.empty();
    }
}
