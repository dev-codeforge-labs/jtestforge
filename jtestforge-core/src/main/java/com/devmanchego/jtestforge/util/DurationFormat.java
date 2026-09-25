package com.devmanchego.jtestforge.util;

/**
 * Renders a millisecond duration as its non-zero leading components - minutes, seconds,
 * remaining milliseconds - rather than a single raw millisecond count that gets hard to
 * read once a call takes more than a few seconds.
 */
public final class DurationFormat {

    private DurationFormat() {
    }

    /** E.g. {@code 577} -> {@code "577ms"}, {@code 130577} -> {@code "2m 10s 577ms"}. */
    public static String humanReadable(long totalMillis) {
        long minutes = totalMillis / 60_000;
        long seconds = (totalMillis % 60_000) / 1000;
        long millis = totalMillis % 1000;

        StringBuilder result = new StringBuilder();
        if (minutes > 0) {
            result.append(minutes).append("m ");
        }
        if (minutes > 0 || seconds > 0) {
            result.append(seconds).append("s ");
        }
        result.append(millis).append("ms");
        return result.toString();
    }
}
