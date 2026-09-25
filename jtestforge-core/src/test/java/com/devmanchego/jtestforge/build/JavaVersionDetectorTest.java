package com.devmanchego.jtestforge.build;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JavaVersionDetectorTest {

    @TempDir
    Path dir;

    private final JavaVersionDetector detector = new JavaVersionDetector(null);

    @Test
    void anExplicitVersionWinsAndIsNormalised() throws IOException {
        writePom(dir, "<properties><maven.compiler.source>17</maven.compiler.source></properties>");

        assertThat(detector.detect(dir, "1.8")).isEqualTo(new DetectedJavaVersion(8, "configured"));
    }

    @Test
    void somethingThatIsNotAJavaVersionIsRejected() {
        assertThatThrownBy(() -> detector.detect(dir, "eight"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("eight");
    }

    @Test
    void theClassicSourcePropertyIsRead() throws IOException {
        writePom(dir, "<properties><maven.compiler.source>1.8</maven.compiler.source>"
                + "<maven.compiler.target>1.8</maven.compiler.target></properties>");

        DetectedJavaVersion detected = detector.detect(dir, null);

        assertThat(detected.release()).isEqualTo(8);
        assertThat(detected.source()).contains("maven.compiler.source").contains("1.8");
    }

    @Test
    void compilerPluginConfigurationWinsAndItsPropertyReferencesAreResolved() throws IOException {
        writePom(dir, "<properties><java.version>11</java.version>"
                + "<maven.compiler.source>1.8</maven.compiler.source></properties>"
                + "<build><plugins><plugin><groupId>org.apache.maven.plugins</groupId>"
                + "<artifactId>maven-compiler-plugin</artifactId>"
                + "<configuration><release>${java.version}</release></configuration>"
                + "</plugin></plugins></build>");

        DetectedJavaVersion detected = detector.detect(dir, null);

        assertThat(detected.release()).isEqualTo(11);
        assertThat(detected.source()).contains("<release>");
    }

    @Test
    void aPropertyInheritedFromTheParentPomOnDiskIsUsed() throws IOException {
        writePom(dir, "<groupId>com.acme</groupId><artifactId>parent</artifactId><version>1</version>"
                + "<packaging>pom</packaging><properties><maven.compiler.release>17</maven.compiler.release></properties>");
        Path module = dir.resolve("module");
        writePom(module, "<parent><groupId>com.acme</groupId><artifactId>parent</artifactId><version>1</version>"
                + "</parent><artifactId>module</artifactId>");

        assertThat(detector.detect(module, null).release()).isEqualTo(17);
    }

    @Test
    void aCorporateParentOnlyInTheLocalRepositoryIsFoundThere() throws IOException {
        Path repository = dir.resolve("repo");
        Path parentPom = repository.resolve("com/corp/corp-parent/5/corp-parent-5.pom");
        Files.createDirectories(parentPom.getParent());
        Files.writeString(parentPom, pom("<groupId>com.corp</groupId><artifactId>corp-parent</artifactId>"
                + "<version>5</version><properties><java.version>1.7</java.version></properties>"));
        Path module = dir.resolve("module");
        writePom(module, "<parent><groupId>com.corp</groupId><artifactId>corp-parent</artifactId><version>5</version>"
                + "<relativePath/></parent><artifactId>module</artifactId>");

        DetectedJavaVersion detected =
                new JavaVersionDetector(new MavenLocalRepository(repository, "test")).detect(module, null);

        assertThat(detected.release()).isEqualTo(7);
    }

    @Test
    void withoutCompilerSettingsTheBuiltClassesDecide() throws IOException {
        writePom(dir, "<groupId>g</groupId><artifactId>a</artifactId><version>1</version>");
        writeClassFile(dir.resolve("target/classes/com/acme/Old.class"), 52);

        DetectedJavaVersion detected = detector.detect(dir, null);

        assertThat(detected.release()).isEqualTo(8);
        assertThat(detected.source()).contains("target/classes").contains("52");
    }

    @Test
    void failingThatTheModulesOwnJarIsInspectedIgnoringMultiReleaseEntries() throws IOException {
        writePom(dir, "<groupId>g</groupId><artifactId>a</artifactId><version>1</version>");
        Path jar = dir.resolve("target/app-1.0.jar");
        Files.createDirectories(jar.getParent());
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
            zip.putNextEntry(new ZipEntry("META-INF/versions/17/com/acme/A.class"));
            zip.write(classHeader(61));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("com/acme/A.class"));
            zip.write(classHeader(55));
            zip.closeEntry();
        }

        DetectedJavaVersion detected = detector.detect(dir, null);

        assertThat(detected.release()).isEqualTo(11);
        assertThat(detected.source()).contains("app-1.0.jar");
    }

    @Test
    void nothingToGoOnIsUnknown() throws IOException {
        writePom(dir, "<groupId>g</groupId><artifactId>a</artifactId><version>1</version>");

        assertThat(detector.detect(dir, null).isKnown()).isFalse();
    }

    @Test
    void aJdksReleaseFileGivesItsFeatureRelease() throws IOException {
        Files.writeString(dir.resolve("release"), "IMPLEMENTOR=\"Oracle Corporation\"\nJAVA_VERSION=\"1.8.0_291\"\n");

        assertThat(JavaVersionDetector.javaHomeRelease(dir)).isEqualTo(8);
        assertThat(JavaVersionDetector.javaHomeRelease(dir.resolve("no-such-jdk"))).isZero();
    }

    @Test
    void javaReleaseSpellingsAreNormalised() {
        assertThat(JavaRelease.parse("1.8")).isEqualTo(8);
        assertThat(JavaRelease.parse(" 11 ")).isEqualTo(11);
        assertThat(JavaRelease.parse("17.0.2")).isEqualTo(17);
        assertThat(JavaRelease.parse("21-ea")).isEqualTo(21);
        assertThat(JavaRelease.parse("1.8.0_291")).isEqualTo(8);
        assertThat(JavaRelease.parse("${java.version}")).isZero();
        assertThat(JavaRelease.parse("123")).isZero();
        assertThat(JavaRelease.parse(null)).isZero();
        assertThat(JavaRelease.fromClassFileMajor(52)).isEqualTo(8);
        assertThat(JavaRelease.fromClassFileMajor(65)).isEqualTo(21);
    }

    private static void writePom(Path directory, String content) throws IOException {
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("pom.xml"), pom(content));
    }

    private static String pom(String content) {
        return "<project xmlns=\"http://maven.apache.org/POM/4.0.0\"><modelVersion>4.0.0</modelVersion>"
                + content + "</project>";
    }

    private static void writeClassFile(Path file, int major) throws IOException {
        Files.createDirectories(file.getParent());
        Files.write(file, classHeader(major));
    }

    private static byte[] classHeader(int major) {
        return new byte[] {(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE, 0, 0, (byte) (major >> 8), (byte) major};
    }
}
