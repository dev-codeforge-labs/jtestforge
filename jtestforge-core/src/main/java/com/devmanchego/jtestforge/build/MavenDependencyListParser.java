package com.devmanchego.jtestforge.build;

import com.devmanchego.jtestforge.model.ModuleDependency;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Parses {@code mvn dependency:list} output into {@link ModuleDependency} records —
 * jtestforge-specification.md §7.3.
 *
 * <p>Each dependency line is {@code groupId:artifactId:type:version:scope}, or
 * {@code groupId:artifactId:type:classifier:version:scope} when a classifier is present.
 * The scope is always last and the version always immediately precedes it, so both are
 * read from the end - which handles the classifier case without needing to detect it.
 */
public final class MavenDependencyListParser {

    private MavenDependencyListParser() {
    }

    public static List<ModuleDependency> parse(String output) {
        List<ModuleDependency> dependencies = new ArrayList<>();
        if (output == null) {
            return dependencies;
        }
        for (String rawLine : output.lines().toList()) {
            String line = stripMavenLogPrefix(rawLine).strip();
            if (line.isEmpty() || !line.contains(":")) {
                continue;
            }
            parseDependencyLine(line).ifPresent(dependencies::add);
        }
        return List.copyOf(dependencies);
    }

    private static java.util.Optional<ModuleDependency> parseDependencyLine(String line) {
        // Trailing annotations such as " -- module java.sql" appear on some Maven
        // versions; everything after whitespace is not part of the coordinate.
        String coordinate = line.split("\\s")[0];
        String[] parts = coordinate.split(":");
        if (parts.length < 5) {
            return java.util.Optional.empty();
        }
        String groupId = parts[0];
        String artifactId = parts[1];
        String scope = parts[parts.length - 1].toLowerCase(Locale.ROOT);
        String version = parts[parts.length - 2];
        if (groupId.isEmpty() || artifactId.isEmpty()) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(new ModuleDependency(groupId, artifactId, version, scope));
    }

    private static String stripMavenLogPrefix(String line) {
        String stripped = line.strip();
        if (stripped.startsWith("[INFO]")) {
            return stripped.substring("[INFO]".length());
        }
        return stripped;
    }
}
