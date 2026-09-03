package com.devmanchego.jtestforge.build;

import com.devmanchego.jtestforge.model.ModuleDependency;
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
 * Exercises a real, offline {@code mvn dependency:list} against jtestforge-core's own
 * module directory - same self-referential approach as {@link MavenClasspathResolverTest}.
 */
class ModuleDependencyResolverTest {

    private static final Duration TIMEOUT = Duration.ofMinutes(2);

    private Path ownModuleDirectory;

    @BeforeEach
    void locateOwnModule() {
        ownModuleDirectory = Path.of("").toAbsolutePath();
        Assumptions.assumeTrue(Files.isRegularFile(ownModuleDirectory.resolve("pom.xml")),
                "This test must run with jtestforge-core as the working directory (mvn does this by default).");
        Assumptions.assumeTrue(
                ExecutableResolver.isResolvable("mvn", ExecutableResolver.systemPathDirectories()),
                "mvn is not on PATH in this environment.");
    }

    @Test
    void resolvesARealDependencyListOfflineAgainstItsOwnModule() {
        ModuleDependencyResolver resolver = new ModuleDependencyResolver(
                new ProcessRunner(), "mvn", List.of("-o", "-B"));

        List<ModuleDependency> dependencies = resolver.resolveDependencies(ownModuleDirectory, TIMEOUT);

        assertThat(dependencies).isNotEmpty();
        assertThat(dependencies).anySatisfy(dependency ->
                assertThat(dependency.hasArtifactId("javaparser-symbol-solver-core")).isTrue());
        assertThat(dependencies).anySatisfy(dependency ->
                assertThat(dependency.hasArtifactId("junit-jupiter-api")).isTrue());
    }

    @Test
    void aBadMavenExecutableFailsWithAClearException() {
        ModuleDependencyResolver resolver = new ModuleDependencyResolver(
                new ProcessRunner(), "definitely-not-a-real-maven-executable", List.of());

        assertThatThrownBy(() -> resolver.resolveDependencies(ownModuleDirectory, TIMEOUT))
                .isInstanceOf(RuntimeException.class);
    }
}
