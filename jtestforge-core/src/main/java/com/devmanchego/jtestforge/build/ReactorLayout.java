package com.devmanchego.jtestforge.build;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The multi-module build a module belongs to, found from the filesystem alone: the
 * topmost ancestor directory that still has a {@code pom.xml} is taken as the reactor
 * root, and its {@code <modules>} are followed recursively.
 *
 * <p>Needed because a dependency on a sibling module usually has no jar in the local
 * repository until someone runs {@code mvn install} - its classes live in the sibling's
 * own {@code target/classes} instead.
 */
public final class ReactorLayout {

    private final Path root;
    private final Map<String, Path> moduleDirectoriesByKey;

    private ReactorLayout(Path root, Map<String, Path> moduleDirectoriesByKey) {
        this.root = root;
        this.moduleDirectoriesByKey = Map.copyOf(moduleDirectoriesByKey);
    }

    public static ReactorLayout discover(Path modulePath) {
        Path moduleDirectory = modulePath.toAbsolutePath().normalize();
        Path root = moduleDirectory;
        for (Path dir = moduleDirectory.getParent(); dir != null && Files.isRegularFile(dir.resolve("pom.xml"));
             dir = dir.getParent()) {
            root = dir;
        }
        Map<String, Path> modules = new LinkedHashMap<>();
        collect(root, modules, new HashSet<>());
        collect(moduleDirectory, modules, new HashSet<>());
        return new ReactorLayout(root, modules);
    }

    private static void collect(Path directory, Map<String, Path> into, Set<Path> visited) {
        Path pomFile = directory.resolve("pom.xml");
        if (!visited.add(directory) || !Files.isRegularFile(pomFile)) {
            return;
        }
        PomInfo pom;
        try {
            pom = PomReader.read(pomFile);
        } catch (RuntimeException e) {
            return;
        }
        into.putIfAbsent(pom.key(), directory);
        for (String module : pom.modules()) {
            Path moduleDirectory = directory.resolve(module).normalize();
            if (Files.isRegularFile(moduleDirectory)) {
                moduleDirectory = moduleDirectory.getParent();
            }
            collect(moduleDirectory, into, visited);
        }
    }

    public Path root() {
        return root;
    }

    public Optional<Path> moduleDirectory(String groupId, String artifactId) {
        return Optional.ofNullable(moduleDirectoriesByKey.get(groupId + ":" + artifactId));
    }
}
