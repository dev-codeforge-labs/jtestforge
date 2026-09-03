package com.devmanchego.jtestforge.util;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Resolves a configured command name (an AI CLI, {@code mvn}, ...) to an actual,
 * launchable executable path.
 *
 * <p>Used for two related but distinct purposes: {@code ConfigValidator} calls
 * {@link #resolve} to check a command is resolvable before a run starts (§5.1);
 * {@code MavenClasspathResolver} and other process launchers call it to get the literal
 * path {@link ProcessBuilder} actually needs. Both need the same answer - a bare name
 * like {@code "mvn"} is not directly launchable via {@link ProcessBuilder} on Windows:
 * unlike a shell, {@code CreateProcess} does not search {@code PATHEXT} extensions for a
 * bare command name, so without this resolution step {@code "mvn"} fails to start even
 * though {@code mvn.cmd} is genuinely on {@code PATH}.
 */
public final class ExecutableResolver {

    // Mirrors the default %PATHEXT% search order closely enough for this purpose: a
    // fixed short list, not a full shell replacement. The bare, extensionless form is
    // checked LAST and not first: many tools (mvn, npm, ...) ship a POSIX shell script
    // under the bare name alongside the real Windows launcher (mvn.cmd), and CreateProcess
    // cannot run that POSIX script at all ("%1 is not a valid Win32 application") - so on
    // Windows, preferring the bare match would pick the one candidate guaranteed to fail.
    private static final List<String> WINDOWS_EXECUTABLE_EXTENSIONS =
            List.of(".cmd", ".bat", ".exe", ".com", "");

    private ExecutableResolver() {
    }

    /**
     * @param command          an absolute path, a relative path containing a separator,
     *                         or a bare name to search {@code pathDirectories} for
     * @param pathDirectories  directories to search, in order, for a bare command name
     * @return the resolved, launchable path, or empty if nothing matched
     */
    public static Optional<Path> resolve(String command, List<String> pathDirectories) {
        if (command == null || command.isBlank()) {
            return Optional.empty();
        }

        Path asPath = Path.of(command);
        if (asPath.isAbsolute()) {
            return findExecutableCandidate(asPath);
        }
        if (command.contains("/") || command.contains("\\")) {
            return findExecutableCandidate(asPath.toAbsolutePath());
        }

        for (String directory : pathDirectories) {
            if (directory == null || directory.isBlank()) {
                continue;
            }
            Path candidateDir = Path.of(directory);
            for (String extension : WINDOWS_EXECUTABLE_EXTENSIONS) {
                Path candidate = candidateDir.resolve(command + extension);
                if (Files.isRegularFile(candidate)) {
                    return Optional.of(candidate);
                }
            }
        }
        return Optional.empty();
    }

    public static boolean isResolvable(String command, List<String> pathDirectories) {
        return resolve(command, pathDirectories).isPresent();
    }

    /** Reads the real process {@code PATH}, split into directories. */
    public static List<String> systemPathDirectories() {
        String path = System.getenv().getOrDefault("Path", System.getenv().getOrDefault("PATH", ""));
        return List.of(path.split(File.pathSeparator));
    }

    private static Optional<Path> findExecutableCandidate(Path path) {
        if (Files.isRegularFile(path)) {
            return Optional.of(path);
        }
        for (String extension : WINDOWS_EXECUTABLE_EXTENSIONS) {
            if (extension.isEmpty()) {
                continue;
            }
            Path withExtension = Path.of(path + extension);
            if (Files.isRegularFile(withExtension)) {
                return Optional.of(withExtension);
            }
        }
        return Optional.empty();
    }
}
