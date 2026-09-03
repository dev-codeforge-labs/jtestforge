package com.devmanchego.jtestforge.model;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A Maven version reduced to its numeric {@code major.minor.patch} head, for the
 * threshold comparisons in jtestforge-specification.md §7.3 (Framework >= 6.2,
 * Boot >= 3.4).
 *
 * <p>Any qualifier is deliberately ignored, so {@code 6.2.0-RC1} compares equal to
 * {@code 6.2.0}. For the question this type exists to answer - "does this version have
 * {@code @MockitoBean}?" - a release candidate of 6.2 does, so treating it as older than
 * 6.2 would produce exactly the wrong generated code.
 */
public record SemanticVersion(int major, int minor, int patch) implements Comparable<SemanticVersion> {

    private static final Pattern NUMERIC_HEAD = Pattern.compile("^(\\d+)(?:\\.(\\d+))?(?:\\.(\\d+))?");

    /** @return empty when the text has no leading numeric component at all. */
    public static Optional<SemanticVersion> parse(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        Matcher matcher = NUMERIC_HEAD.matcher(text.strip());
        if (!matcher.find()) {
            return Optional.empty();
        }
        return Optional.of(new SemanticVersion(
                Integer.parseInt(matcher.group(1)),
                matcher.group(2) == null ? 0 : Integer.parseInt(matcher.group(2)),
                matcher.group(3) == null ? 0 : Integer.parseInt(matcher.group(3))));
    }

    public boolean isAtLeast(int otherMajor, int otherMinor) {
        return compareTo(new SemanticVersion(otherMajor, otherMinor, 0)) >= 0;
    }

    @Override
    public int compareTo(SemanticVersion other) {
        int byMajor = Integer.compare(major, other.major);
        if (byMajor != 0) {
            return byMajor;
        }
        int byMinor = Integer.compare(minor, other.minor);
        return byMinor != 0 ? byMinor : Integer.compare(patch, other.patch);
    }

    @Override
    public String toString() {
        return major + "." + minor + "." + patch;
    }
}
