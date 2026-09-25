package com.devmanchego.jtestforge.util;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code JAVA_HOME} alone is not enough to make a launcher script actually run on the
 * configured JDK - see the class Javadoc. These tests are about the {@code PATH} half of
 * that, which is easy to get backwards (appending instead of prepending defeats the whole
 * point: the configured JDK must be found *before* whatever was already on {@code PATH}).
 */
class JavaHomeEnvironmentTest {

    @Test
    void aNullJavaHomeOverridesNothing() {
        assertThat(JavaHomeEnvironment.overridesFor(null)).isEmpty();
    }

    @Test
    void javaHomeIsExportedVerbatim() {
        Path javaHome = Path.of("C:/tools/java/openjdk8-temurin");

        Map<String, String> overrides = JavaHomeEnvironment.overridesFor(javaHome);

        assertThat(overrides).containsEntry("JAVA_HOME", javaHome.toString());
    }

    @Test
    void theConfiguredJdksBinDirectoryIsPrependedToPathNotAppended() {
        Path javaHome = Path.of("C:/tools/java/openjdk8-temurin");

        String path = JavaHomeEnvironment.overridesFor(javaHome).get("PATH");

        assertThat(path).startsWith(javaHome.resolve("bin").toString() + File.pathSeparator);
    }

    @Test
    void theInheritedPathIsPreservedAfterTheConfiguredJdksBinDirectory() {
        Path javaHome = Path.of("C:/tools/java/openjdk8-temurin");
        String inheritedPath = currentPathFromEnvironment();

        String path = JavaHomeEnvironment.overridesFor(javaHome).get("PATH");

        assertThat(path).endsWith(inheritedPath);
    }

    private static String currentPathFromEnvironment() {
        for (Map.Entry<String, String> entry : System.getenv().entrySet()) {
            if (entry.getKey().equalsIgnoreCase("PATH")) {
                return entry.getValue();
            }
        }
        return "";
    }
}
