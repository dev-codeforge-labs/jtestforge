package com.devmanchego.jtestforge.orchestration;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import java.util.Optional;

/**
 * Keeps a copy of each of the developer's test files exactly as it was before this run first
 * touched it - {@code execution.backupOriginalTests}, jtestforge-specification.md §9 step 2.
 *
 * <p>A safety net, nothing more: units are reverted by subtracting what they added
 * ({@code TestClassReverter}), never by restoring a backup, because a backup would also throw
 * away what earlier units of the same run legitimately kept. This is for the case nothing else
 * covers - a bug, a disk full, a developer who regrets the run - so it must hold the
 * <em>original</em> bytes: once a file has a backup, later units and resumed runs leave it alone.
 *
 * <p>Copies land in {@code <backupRoot>/<path of the file relative to the module>}; a test class
 * the run creates has nothing to back up, and the run's own files are deleted by {@code clean}
 * with the rest of the state directory.
 */
public final class TestFileBackup {

    private final Path backupRoot;
    private final Path modulePath;

    /**
     * @param backupRoot where this run's copies go, e.g. {@code <stateDir>/backups/<runId>}
     * @param modulePath the module directory, to keep the copies' relative layout
     */
    public TestFileBackup(Path backupRoot, Path modulePath) {
        this.backupRoot = Objects.requireNonNull(backupRoot, "backupRoot");
        this.modulePath = Objects.requireNonNull(modulePath, "modulePath").toAbsolutePath().normalize();
    }

    /**
     * Copies {@code testFile} if it exists and has not been backed up yet.
     *
     * @return the copy, or empty when there was nothing to do
     * @throws UncheckedIOException if the copy could not be made
     */
    public Optional<Path> backupOnce(String testFile) {
        Path source = ModulePaths.resolve(modulePath, testFile);
        if (!Files.isRegularFile(source)) {
            return Optional.empty();
        }
        Path copy = backupRoot.resolve(relativeToModule(source));
        if (Files.exists(copy)) {
            return Optional.empty();
        }
        try {
            Files.createDirectories(copy.getParent());
            Files.copy(source, copy, StandardCopyOption.COPY_ATTRIBUTES);
            return Optional.of(copy);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to back up " + source + " to " + copy, e);
        }
    }

    /** Relative to the module when inside it; otherwise just the file name, never an absolute path. */
    private Path relativeToModule(Path source) {
        return source.startsWith(modulePath) ? modulePath.relativize(source) : source.getFileName();
    }
}
