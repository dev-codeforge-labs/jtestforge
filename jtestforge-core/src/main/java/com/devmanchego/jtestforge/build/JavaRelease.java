package com.devmanchego.jtestforge.build;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Java feature-release numbers from the spellings Maven, JDKs and class files use. */
public final class JavaRelease {

    private static final Pattern LEADING_NUMBER = Pattern.compile("^(\\d{1,2})(?!\\d)");
    private static final int FIRST_CLASS_FILE_MAJOR = 44;

    private JavaRelease() {
    }

    /** {@code "1.8"} → 8, {@code "11"} → 11, {@code "17.0.2"} → 17, {@code "1.8.0_291"} → 8; 0 if not a version. */
    public static int parse(String value) {
        if (value == null) {
            return 0;
        }
        String candidate = value.strip();
        if (candidate.startsWith("1.")) {
            candidate = candidate.substring(2);
        }
        Matcher matcher = LEADING_NUMBER.matcher(candidate);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : 0;
    }

    /** Class file major version 52 → 8, 55 → 11, 65 → 21. */
    public static int fromClassFileMajor(int major) {
        return major > FIRST_CLASS_FILE_MAJOR ? major - FIRST_CLASS_FILE_MAJOR : 0;
    }
}
