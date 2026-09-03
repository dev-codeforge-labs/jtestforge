package com.devmanchego.jtestforge.build;

import com.devmanchego.jtestforge.util.ExecutableResolver;
import com.devmanchego.jtestforge.util.ProcessRunner;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exercises a real, offline {@code mvn dependency:build-classpath} rather than a mocked
 * process, run against jtestforge-core's own module directory - self-referential, like
 * the fat-jar proof in phase 0, and possible without a network since this project's own
 * dependencies are already resolved into the local repository by the time this test runs.
 */
class MavenClasspathResolverTest {

    private static final Duration TIMEOUT = Duration.ofMinutes(2);

    private Path ownModuleDirectory;

    @BeforeEach
    void locateOwnModule() {
        // jtestforge-core/target/test-classes -> ... -> jtestforge-core
        ownModuleDirectory = Path.of("").toAbsolutePath();
        Assumptions.assumeTrue(Files.isRegularFile(ownModuleDirectory.resolve("pom.xml")),
                "This test must run with jtestforge-core as the working directory (mvn does this by default).");
        Assumptions.assumeTrue(
                ExecutableResolver.isResolvable("mvn", ExecutableResolver.systemPathDirectories()),
                "mvn is not on PATH in this environment.");
    }

    @Test
    void resolvesARealClasspathOfflineAgainstItsOwnModule() {
        MavenClasspathResolver resolver = new MavenClasspathResolver(
                new ProcessRunner(), "mvn", List.of("-o", "-B"));

        List<Path> classpath = resolver.resolveCompileClasspath(ownModuleDirectory, TIMEOUT);

        assertThat(classpath).isNotEmpty();
        // jtestforge-core's own compile dependencies include picocli-independent libs
        // such as JavaParser; the exact set matters less than proving every returned
        // entry is a real, existing jar Maven actually resolved.
        assertThat(classpath).allSatisfy(entry -> assertThat(Files.exists(entry)).isTrue());
        assertThat(classpath).anyMatch(entry -> entry.toString().contains("javaparser"));
    }

    @Test
    void aBadMavenExecutableFailsWithAClearException() {
        MavenClasspathResolver resolver = new MavenClasspathResolver(
                new ProcessRunner(), "definitely-not-a-real-maven-executable", List.of());

        assertThatThrownBy(() -> resolver.resolveCompileClasspath(ownModuleDirectory, TIMEOUT))
                .isInstanceOf(RuntimeException.class);
    }
}
