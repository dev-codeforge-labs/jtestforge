package com.devmanchego.jtestforge.build;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parsed {@code mvn dependency:tree} output for one or many modules, keyed by each tree's
 * root {@code groupId:artifactId}.
 *
 * <p>Accepts both forms Maven produces: the {@code -DoutputFile} file (bare tree lines,
 * one block per module when {@code -DappendOutput=true}) and a captured console log
 * ({@code [INFO] }-prefixed and interleaved with reactor noise). A module's block starts
 * at its root line - a lone {@code groupId:artifactId:packaging:version} - and ends at the
 * first line that is neither a tree line nor blank.
 */
public final class DependencyTree {

    private static final Pattern CHILD_LINE = Pattern.compile("^((?:\\|  |   )*)[+\\\\]- (.+)$");
    private static final Pattern ANSI_ESCAPE = Pattern.compile("\\[[;\\d]*m");
    private static final String INFO_PREFIX = "[INFO]";

    private final Map<String, List<DependencyTreeEntry>> entriesByModule;

    private DependencyTree(Map<String, List<DependencyTreeEntry>> entriesByModule) {
        this.entriesByModule = entriesByModule;
    }

    /** @throws DependencyTreeException if the file cannot be read */
    public static DependencyTree read(Path file) {
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(file);
        } catch (IOException e) {
            throw new DependencyTreeException("Cannot read dependency tree file " + file + ": " + e, e);
        }
        return parse(decode(bytes));
    }

    public static DependencyTree parse(String text) {
        Map<String, List<DependencyTreeEntry>> byModule = new LinkedHashMap<>();
        List<DependencyTreeEntry> current = null;
        for (String rawLine : text.lines().toList()) {
            String line = withoutLogPrefix(ANSI_ESCAPE.matcher(rawLine).replaceAll(""));
            if (line == null || line.isBlank()) {
                continue;
            }
            Matcher child = CHILD_LINE.matcher(line);
            if (child.matches()) {
                if (current != null) {
                    parseEntry(child.group(2)).ifPresent(current::add);
                }
                continue;
            }
            Optional<String> rootKey = rootKey(line);
            if (rootKey.isPresent()) {
                current = new ArrayList<>();
                byModule.put(rootKey.get(), current);
            } else {
                current = null;
            }
        }
        Map<String, List<DependencyTreeEntry>> immutable = new LinkedHashMap<>();
        byModule.forEach((key, entries) -> immutable.put(key, List.copyOf(entries)));
        return new DependencyTree(immutable);
    }

    public Optional<List<DependencyTreeEntry>> module(String groupId, String artifactId) {
        return Optional.ofNullable(entriesByModule.get(groupId + ":" + artifactId));
    }

    public Set<String> moduleKeys() {
        return entriesByModule.keySet();
    }

    /**
     * Windows PowerShell 5.1's {@code >} writes UTF-16 with a BOM, so the BOM decides; with
     * none, ISO-8859-1 never fails and keeps every ASCII coordinate intact.
     */
    private static String decode(byte[] bytes) {
        if (startsWith(bytes, 0xFF, 0xFE)) {
            return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16LE);
        }
        if (startsWith(bytes, 0xFE, 0xFF)) {
            return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16BE);
        }
        if (startsWith(bytes, 0xEF, 0xBB, 0xBF)) {
            return new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
        }
        return new String(bytes, StandardCharsets.ISO_8859_1);
    }

    private static boolean startsWith(byte[] bytes, int... prefix) {
        if (bytes.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if ((bytes[i] & 0xff) != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    /** @return the tree part of the line, or null for any other log level ([WARNING], [ERROR]...) */
    private static String withoutLogPrefix(String line) {
        if (line.startsWith(INFO_PREFIX)) {
            String rest = line.substring(INFO_PREFIX.length());
            return rest.startsWith(" ") ? rest.substring(1) : rest;
        }
        return line.startsWith("[") ? null : line;
    }

    private static Optional<String> rootKey(String line) {
        String token = line.strip();
        if (token.isEmpty() || !Character.isLetterOrDigit(token.charAt(0))
                || token.chars().anyMatch(Character::isWhitespace)) {
            return Optional.empty();
        }
        String[] parts = token.split(":", -1);
        if (parts.length != 4) {
            return Optional.empty();
        }
        for (String part : parts) {
            if (part.isEmpty()) {
                return Optional.empty();
            }
        }
        return Optional.of(parts[0] + ":" + parts[1]);
    }

    /**
     * {@code g:a:type:version:scope} or {@code g:a:type:classifier:version:scope}, possibly
     * followed by annotations such as {@code (optional)}. A line wrapped in parentheses is
     * {@code -Dverbose} output for an artifact Maven omitted, so it is not on the classpath.
     */
    private static Optional<DependencyTreeEntry> parseEntry(String text) {
        String trimmed = text.strip();
        if (trimmed.startsWith("(")) {
            return Optional.empty();
        }
        String[] parts = trimmed.split("\\s+")[0].split(":", -1);
        String classifier;
        String version;
        String scope;
        if (parts.length == 5) {
            classifier = "";
            version = parts[3];
            scope = parts[4];
        } else if (parts.length == 6) {
            classifier = parts[3];
            version = parts[4];
            scope = parts[5];
        } else {
            return Optional.empty();
        }
        if (parts[0].isEmpty() || parts[1].isEmpty() || version.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new DependencyTreeEntry(
                parts[0], parts[1], parts[2], classifier, version, scope.toLowerCase(Locale.ROOT)));
    }
}
