package com.devmanchego.jtestforge.build;

import com.devmanchego.jtestforge.model.ModuleDependency;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Stands in for both {@code mvn dependency:build-classpath} ({@link MavenClasspathResolver})
 * and {@code mvn dependency:list} ({@link ModuleDependencyResolver}) using a saved
 * {@code mvn dependency:tree} output, so neither Maven call is needed.
 *
 * <p>The tree may cover a whole multi-module application: the target module's block is
 * picked by its own pom's {@code groupId:artifactId}. Coordinates become paths through the
 * local repository's standard layout, except a dependency on another module of the same
 * reactor, which resolves to that module's {@code target/classes} when it has been built.
 */
public final class DependencyTreeResolver {

    private static final Set<String> JAR_TYPES =
            Set.of("jar", "bundle", "maven-plugin", "ejb", "ejb-client", "test-jar");

    private final String moduleKey;
    private final List<DependencyTreeEntry> entries;
    private final MavenLocalRepository localRepository;
    private final ReactorLayout reactor;

    DependencyTreeResolver(String moduleKey, List<DependencyTreeEntry> entries,
                           MavenLocalRepository localRepository, ReactorLayout reactor) {
        this.moduleKey = moduleKey;
        this.entries = distinct(entries);
        this.localRepository = localRepository;
        this.reactor = reactor;
    }

    /** @throws DependencyTreeException if the tree is unreadable or has no block for the module */
    public static DependencyTreeResolver forModule(Path treeFile, Path modulePath, MavenLocalRepository localRepository) {
        Path pomFile = modulePath.resolve("pom.xml");
        PomInfo pom;
        try {
            pom = PomReader.read(pomFile);
        } catch (RuntimeException e) {
            throw new DependencyTreeException("Cannot read " + pomFile + " to identify the module: " + e.getMessage(), e);
        }
        DependencyTree tree = DependencyTree.read(treeFile);
        List<DependencyTreeEntry> moduleEntries = tree.module(pom.groupId(), pom.artifactId())
                .orElseThrow(() -> new DependencyTreeException("Module " + pom.key() + " does not appear in "
                        + treeFile + " - modules found there: "
                        + (tree.moduleKeys().isEmpty()
                                ? "none (is it the output of mvn dependency:tree?)"
                                : String.join(", ", tree.moduleKeys()))));
        return new DependencyTreeResolver(pom.key(), moduleEntries, localRepository, ReactorLayout.discover(modulePath));
    }

    public String moduleKey() {
        return moduleKey;
    }

    public List<ModuleDependency> dependencies() {
        return entries.stream().map(DependencyTreeEntry::toModuleDependency).toList();
    }

    /**
     * Every scope is included, matching {@code dependency:build-classpath}'s default. A
     * dependency whose file is missing is reported rather than fatal: the scan degrades
     * to name-only resolution for its types instead of refusing to run.
     */
    public ResolvedClasspath compileClasspath() {
        List<Path> resolved = new ArrayList<>();
        List<String> unresolved = new ArrayList<>();
        for (DependencyTreeEntry entry : entries) {
            if (!JAR_TYPES.contains(entry.type())) {
                continue;
            }
            if ("system".equals(entry.scope())) {
                unresolved.add(entry.coordinate() + " - system scope; its path is not recorded in a dependency tree");
                continue;
            }
            Optional<Path> reactorModule = reactor.moduleDirectory(entry.groupId(), entry.artifactId());
            Optional<Path> reactorClasses = reactorModule
                    .map(directory -> directory.resolve("target").resolve("classes"))
                    .filter(Files::isDirectory);
            if (reactorClasses.isPresent()) {
                resolved.add(reactorClasses.get());
                continue;
            }
            String classifier = entry.classifier().isEmpty() && "test-jar".equals(entry.type())
                    ? "tests" : entry.classifier();
            Path jar = localRepository.artifact(entry.groupId(), entry.artifactId(), entry.version(), classifier, "jar");
            if (Files.isRegularFile(jar)) {
                resolved.add(jar);
            } else {
                unresolved.add(entry.coordinate() + " - not found at " + jar
                        + (reactorModule.isPresent() ? " (module of this reactor, not built yet)" : ""));
            }
        }
        return new ResolvedClasspath(resolved, unresolved);
    }

    private static List<DependencyTreeEntry> distinct(List<DependencyTreeEntry> entries) {
        Map<String, DependencyTreeEntry> byCoordinate = new LinkedHashMap<>();
        for (DependencyTreeEntry entry : entries) {
            byCoordinate.putIfAbsent(entry.groupId() + ":" + entry.artifactId() + ":" + entry.classifier(), entry);
        }
        return List.copyOf(byCoordinate.values());
    }
}
