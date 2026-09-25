package com.devmanchego.jtestforge.build;

import com.devmanchego.jtestforge.model.ModuleDependency;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A two-module reactor ({@code web} depends on its sibling {@code lib}) plus a fake local
 * repository, with one dependency tree file covering the whole application.
 */
class DependencyTreeResolverTest {

    private static final String PARENT =
            "<parent><groupId>com.acme</groupId><artifactId>app</artifactId><version>1.0</version></parent>";

    @TempDir
    Path workspace;

    private Path reactorRoot;
    private MavenLocalRepository repository;
    private Path treeFile;

    @BeforeEach
    void createReactorRepositoryAndTree() throws IOException {
        reactorRoot = workspace.resolve("app");
        writePom(reactorRoot, "<groupId>com.acme</groupId><artifactId>app</artifactId><version>1.0</version>"
                + "<packaging>pom</packaging><modules><module>lib</module><module>web</module></modules>");
        writePom(reactorRoot.resolve("lib"), PARENT + "<artifactId>lib</artifactId>");
        Files.createDirectories(reactorRoot.resolve("lib/target/classes"));
        writePom(reactorRoot.resolve("web"), PARENT + "<artifactId>web</artifactId>");

        Path repositoryRoot = workspace.resolve("repo");
        touch(repositoryRoot.resolve("org/slf4j/slf4j-api/1.7.36/slf4j-api-1.7.36.jar"));
        touch(repositoryRoot.resolve("org/x/tests-lib/1.0/tests-lib-1.0-tests.jar"));
        repository = new MavenLocalRepository(repositoryRoot, "test");

        treeFile = workspace.resolve("deps-tree.txt");
        Files.writeString(treeFile, """
                com.acme:app:pom:1.0
                com.acme:lib:jar:1.0
                \\- org.slf4j:slf4j-api:jar:1.7.36:compile
                com.acme:web:jar:1.0
                +- com.acme:lib:jar:1.0:compile
                |  \\- org.slf4j:slf4j-api:jar:1.7.36:compile
                +- com.missing:gone:jar:2.0:compile
                +- com.oracle:ojdbc8:jar:19.3:system
                +- org.x:tests-lib:test-jar:tests:1.0:test
                +- com.acme:bom:pom:1.0:import
                \\- junit:junit:jar:4.13.2:test
                """);
    }

    @Test
    void theModulesOwnBlockIsPickedOutOfAWholeApplicationTree() {
        DependencyTreeResolver resolver = DependencyTreeResolver.forModule(treeFile, reactorRoot.resolve("web"), repository);

        assertThat(resolver.moduleKey()).isEqualTo("com.acme:web");
        assertThat(resolver.dependencies())
                .extracting(ModuleDependency::artifactId, ModuleDependency::scope)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("lib", "compile"),
                        org.assertj.core.groups.Tuple.tuple("slf4j-api", "compile"),
                        org.assertj.core.groups.Tuple.tuple("gone", "compile"),
                        org.assertj.core.groups.Tuple.tuple("ojdbc8", "system"),
                        org.assertj.core.groups.Tuple.tuple("tests-lib", "test"),
                        org.assertj.core.groups.Tuple.tuple("bom", "import"),
                        org.assertj.core.groups.Tuple.tuple("junit", "test"));
    }

    @Test
    void aSiblingResolvesToItsBuiltClassesAndEverythingElseToTheLocalRepository() {
        ResolvedClasspath classpath =
                DependencyTreeResolver.forModule(treeFile, reactorRoot.resolve("web"), repository).compileClasspath();

        assertThat(classpath.entries()).containsExactly(
                reactorRoot.resolve("lib/target/classes"),
                repository.path().resolve("org/slf4j/slf4j-api/1.7.36/slf4j-api-1.7.36.jar"),
                repository.path().resolve("org/x/tests-lib/1.0/tests-lib-1.0-tests.jar"));
        assertThat(classpath.unresolved()).hasSize(3)
                .anySatisfy(line -> assertThat(line).startsWith("com.missing:gone:jar:2.0:compile - not found at"))
                .anySatisfy(line -> assertThat(line).startsWith("com.oracle:ojdbc8").contains("system scope"))
                .anySatisfy(line -> assertThat(line).startsWith("junit:junit:jar:4.13.2:test - not found at"));
    }

    @Test
    void aSiblingThatWasNeverBuiltIsReportedAsSuch() throws IOException {
        Files.delete(reactorRoot.resolve("lib/target/classes"));

        ResolvedClasspath classpath =
                DependencyTreeResolver.forModule(treeFile, reactorRoot.resolve("web"), repository).compileClasspath();

        assertThat(classpath.unresolved()).anySatisfy(line -> assertThat(line)
                .startsWith("com.acme:lib:jar:1.0:compile - not found at")
                .endsWith("(module of this reactor, not built yet)"));
    }

    @Test
    void aTreeWithoutTheModuleFailsNamingTheModulesItDoesCover() throws IOException {
        Path other = workspace.resolve("other");
        writePom(other, "<groupId>com.acme</groupId><artifactId>other</artifactId><version>1.0</version>");

        assertThatThrownBy(() -> DependencyTreeResolver.forModule(treeFile, other, repository))
                .isInstanceOf(DependencyTreeException.class)
                .hasMessageContaining("com.acme:other")
                .hasMessageContaining("com.acme:web");
    }

    private static void writePom(Path directory, String content) throws IOException {
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("pom.xml"), "<project xmlns=\"http://maven.apache.org/POM/4.0.0\">"
                + "<modelVersion>4.0.0</modelVersion>" + content + "</project>");
    }

    private static void touch(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        Files.write(file, new byte[0]);
    }
}
