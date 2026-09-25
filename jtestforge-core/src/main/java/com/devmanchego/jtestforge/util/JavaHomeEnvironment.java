package com.devmanchego.jtestforge.util;

import java.io.File;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The process-environment overrides that make a child process actually run on
 * {@code project.javaHome}, not just believe it should.
 *
 * <p>Setting {@code JAVA_HOME} alone is not enough: a launcher script only honours it if it
 * explicitly reads {@code %JAVA_HOME%\bin\java.exe} rather than resolving a bare
 * {@code java} off {@code PATH} - and a corporate {@code mvn.cmd} wrapper (one carrying its
 * own proxy/toolchain setup ahead of the real Maven launcher) is exactly the kind of script
 * that might not. Putting {@code <javaHome>\bin} at the very front of {@code PATH} as well
 * means the configured JDK wins even then, taking precedence over whatever the invoking
 * shell already had there - which is the whole point of configuring it in
 * {@code jtestforge.yaml} in the first place.
 */
public final class JavaHomeEnvironment {

    private JavaHomeEnvironment() {
    }

    /** @return {@code JAVA_HOME} and a {@code PATH} with {@code javaHome}'s {@code bin} directory prepended; empty when {@code javaHome} is null */
    public static Map<String, String> overridesFor(Path javaHome) {
        if (javaHome == null) {
            return Map.of();
        }
        Map<String, String> environment = new LinkedHashMap<>();
        environment.put("JAVA_HOME", javaHome.toString());
        environment.put("PATH", javaHome.resolve("bin") + File.pathSeparator + currentPath());
        return environment;
    }

    /**
     * Windows environment variable lookup is case-insensitive, but {@link System#getenv()}
     * itself is not: the real key can be {@code "Path"}, {@code "PATH"}, or (rarely)
     * something else entirely depending on how the parent process set it.
     */
    private static String currentPath() {
        Map<String, String> env = System.getenv();
        for (Map.Entry<String, String> entry : env.entrySet()) {
            if (entry.getKey().equalsIgnoreCase("PATH")) {
                return entry.getValue();
            }
        }
        return "";
    }
}
