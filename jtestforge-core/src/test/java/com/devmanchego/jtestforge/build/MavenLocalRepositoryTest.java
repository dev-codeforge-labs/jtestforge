package com.devmanchego.jtestforge.build;

import com.devmanchego.jtestforge.util.ExecutableResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/** Every case passes its own environment and home, so the machine's real Maven setup never leaks in. */
class MavenLocalRepositoryTest {

    private static final Supplier<Optional<Path>> NEVER_CALLED =
            () -> fail("mvn should not have been searched for - an earlier answer already settled it");

    @TempDir
    Path home;

    @Test
    void anExplicitSettingWinsOverEverythingElseWithoutEverSearchingForMvn() throws IOException {
        writeSettings(home.resolve(".m2/settings.xml"), home.resolve("from-settings").toString());

        MavenLocalRepository repository = MavenLocalRepository.detect(home.resolve("explicit").toString(),
                List.of("-Dmaven.repo.local=" + home.resolve("from-args")), home, Map.of(), NEVER_CALLED);

        assertThat(repository.path()).isEqualTo(home.resolve("explicit"));
        assertThat(repository.source()).isEqualTo("project.localRepository");
    }

    @Test
    void mavenRepoLocalInMavenArgsBeatsSettingsFilesWithoutEverSearchingForMvn() throws IOException {
        writeSettings(home.resolve(".m2/settings.xml"), home.resolve("from-settings").toString());

        MavenLocalRepository repository = MavenLocalRepository.detect(null,
                List.of("-o", "-Dmaven.repo.local=" + home.resolve("from-args")), home, Map.of(), NEVER_CALLED);

        assertThat(repository.path()).isEqualTo(home.resolve("from-args"));
    }

    @Test
    void theUserSettingsFileIsReadWithUserHomeExpandedWithoutEverSearchingForMvn() throws IOException {
        writeSettings(home.resolve(".m2/settings.xml"), "${user.home}/custom-repo");

        MavenLocalRepository repository = MavenLocalRepository.detect(null, List.of(), home, Map.of(), NEVER_CALLED);

        assertThat(repository.path()).isEqualTo(home.resolve("custom-repo"));
    }

    /**
     * A malformed PATH entry (a stray quote, seen from a corporate JAVA_HOME/PATH setup)
     * must not abort resolution when the user's own settings.xml already answers it - the
     * search for {@code mvn} is only reached at all once every earlier source has missed.
     */
    @Test
    void aMalformedPathEntryNeverSurfacesWhenTheUserSettingsFileAlreadyAnswers() throws IOException {
        writeSettings(home.resolve(".m2/settings.xml"), home.resolve("user-repo").toString());

        MavenLocalRepository repository = MavenLocalRepository.detect(null, List.of(), home, Map.of(),
                () -> ExecutableResolver.resolve("mvn", List.of("\"C:\\tools\\java\\openjdk8-temurin\"\\bin")));

        assertThat(repository.path()).isEqualTo(home.resolve("user-repo"));
    }

    @Test
    void theInstallationsGlobalSettingsAreFoundFromTheMvnLauncher(@TempDir Path mavenHome) throws IOException {
        Path launcher = mavenHome.resolve("bin/mvn.cmd");
        Files.createDirectories(launcher.getParent());
        Files.writeString(launcher, "");
        writeSettings(mavenHome.resolve("conf/settings.xml"), mavenHome.resolve("global-repo").toString());

        MavenLocalRepository repository =
                MavenLocalRepository.detect(null, List.of(), home, Map.of(), () -> Optional.of(launcher));

        assertThat(repository.path()).isEqualTo(mavenHome.resolve("global-repo"));
        assertThat(repository.source()).endsWith("settings.xml");
    }

    @Test
    void userSettingsWinOverGlobalSettingsWithoutEverSearchingForMvn(@TempDir Path mavenHome) throws IOException {
        writeSettings(home.resolve(".m2/settings.xml"), home.resolve("user-repo").toString());
        writeSettings(mavenHome.resolve("conf/settings.xml"), mavenHome.resolve("global-repo").toString());

        MavenLocalRepository repository = MavenLocalRepository.detect(null, List.of(), home,
                Map.of("MAVEN_HOME", mavenHome.toString()), NEVER_CALLED);

        assertThat(repository.path()).isEqualTo(home.resolve("user-repo"));
    }

    @Test
    void aCommentedOutLocalRepositoryFallsBackToTheMavenDefault() throws IOException {
        Files.createDirectories(home.resolve(".m2"));
        Files.writeString(home.resolve(".m2/settings.xml"),
                "<settings><!-- <localRepository>/not/this</localRepository> --></settings>");

        MavenLocalRepository repository = MavenLocalRepository.detect(null, List.of(), home, Map.of(), Optional::empty);

        assertThat(repository.path()).isEqualTo(home.resolve(".m2").resolve("repository"));
    }

    @Test
    void environmentReferencesAreExpandedWithoutEverSearchingForMvn() {
        MavenLocalRepository repository = MavenLocalRepository.detect("${env.REPO_ROOT}/m2", List.of(), home,
                Map.of("REPO_ROOT", home.toString()), NEVER_CALLED);

        assertThat(repository.path()).isEqualTo(home.resolve("m2"));
    }

    @Test
    void artifactsFollowTheStandardRepositoryLayout() {
        MavenLocalRepository repository = new MavenLocalRepository(home, "test");

        assertThat(repository.artifact("org.apache.commons", "commons-lang3", "3.12.0", "", "jar"))
                .isEqualTo(home.resolve("org/apache/commons/commons-lang3/3.12.0/commons-lang3-3.12.0.jar"));
        assertThat(repository.artifact("com.acme", "shared", "1.0", "tests", "jar"))
                .isEqualTo(home.resolve("com/acme/shared/1.0/shared-1.0-tests.jar"));
    }

    private static void writeSettings(Path file, String localRepository) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, "<settings xmlns=\"http://maven.apache.org/SETTINGS/1.2.0\">\n"
                + "  <localRepository>" + localRepository + "</localRepository>\n</settings>\n");
    }
}
