package com.devmanchego.jtestforge.build;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A module's pom and its ancestors, read without running Maven - the part of
 * {@link JavaVersionDetector} that {@link SourceEncodingDetector} needs just as much: which
 * parent poms exist (on disk via {@code relativePath}, else in the local repository), the
 * properties they inherit, and {@code ${...}} resolution.
 */
final class PomChain {

    private static final int MAX_PARENT_DEPTH = 20;
    private static final int MAX_INTERPOLATION_ROUNDS = 10;
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^}]+)}");

    private final MavenLocalRepository localRepository;

    /** @param localRepository where parent poms not found on disk are looked up; may be null */
    PomChain(MavenLocalRepository localRepository) {
        this.localRepository = localRepository;
    }

    /** The module's pom first, then each ancestor that can be found. */
    List<PomInfo> read(Path pomFile) {
        List<PomInfo> chain = new ArrayList<>();
        Set<Path> seen = new HashSet<>();
        Path current = pomFile;
        while (current != null && chain.size() < MAX_PARENT_DEPTH && seen.add(current.toAbsolutePath().normalize())) {
            PomInfo pom;
            try {
                pom = PomReader.read(current);
            } catch (RuntimeException e) {
                break;
            }
            chain.add(pom);
            current = pom.parent() == null ? null : locateParent(pom).orElse(null);
        }
        return chain;
    }

    /** Properties of the whole chain; a child's value wins over its parent's, as in Maven. */
    static Map<String, String> properties(List<PomInfo> chain) {
        Map<String, String> properties = new HashMap<>();
        for (int i = chain.size() - 1; i >= 0; i--) {
            properties.putAll(chain.get(i).properties());
        }
        return properties;
    }

    /** {@code maven-compiler-plugin} configuration of the whole chain; the child wins. */
    static Map<String, String> compilerConfiguration(List<PomInfo> chain) {
        Map<String, String> compiler = new HashMap<>();
        for (int i = chain.size() - 1; i >= 0; i--) {
            compiler.putAll(chain.get(i).compilerConfiguration());
        }
        return compiler;
    }

    private Optional<Path> locateParent(PomInfo child) {
        PomInfo.ParentReference parent = child.parent();
        if (!parent.relativePath().isEmpty()) {
            Path candidate = child.directory().resolve(parent.relativePath()).normalize();
            if (Files.isDirectory(candidate)) {
                candidate = candidate.resolve("pom.xml");
            }
            if (Files.isRegularFile(candidate) && declaresArtifact(candidate, parent.artifactId())) {
                return Optional.of(candidate);
            }
        }
        if (localRepository != null && parent.groupId() != null && parent.artifactId() != null
                && parent.version() != null) {
            Path fromRepository = localRepository.artifact(
                    parent.groupId(), parent.artifactId(), parent.version(), "", "pom");
            if (Files.isRegularFile(fromRepository)) {
                return Optional.of(fromRepository);
            }
        }
        return Optional.empty();
    }

    private static boolean declaresArtifact(Path pomFile, String artifactId) {
        try {
            return artifactId != null && artifactId.equals(PomReader.read(pomFile).artifactId());
        } catch (RuntimeException e) {
            return false;
        }
    }

    static String interpolate(String raw, Map<String, String> properties) {
        String value = raw;
        for (int round = 0; round < MAX_INTERPOLATION_ROUNDS && value.contains("${"); round++) {
            Matcher matcher = PLACEHOLDER.matcher(value);
            StringBuilder resolved = new StringBuilder();
            boolean changed = false;
            while (matcher.find()) {
                String replacement = properties.get(matcher.group(1));
                changed |= replacement != null;
                matcher.appendReplacement(resolved,
                        Matcher.quoteReplacement(replacement != null ? replacement : matcher.group()));
            }
            matcher.appendTail(resolved);
            value = resolved.toString();
            if (!changed) {
                break;
            }
        }
        return value;
    }
}
