package com.devmanchego.jtestforge.build;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SourceEncodingDetectorTest {

    @TempDir
    Path dir;

    private final SourceEncodingDetector detector = new SourceEncodingDetector(null);

    @Test
    void anExplicitEncodingWinsOverWhateverThePomSays() throws IOException {
        writePom(dir, "<properties><project.build.sourceEncoding>UTF-8</project.build.sourceEncoding></properties>");

        DetectedSourceEncoding detected = detector.detect(dir, "windows-1252");

        assertThat(detected.charset().name()).isEqualTo("windows-1252");
        assertThat(detected.source()).isEqualTo("configured");
        assertThat(detected.declared()).isTrue();
    }

    @Test
    void somethingThatIsNotACharsetIsRejectedWhenConfiguredExplicitly() {
        assertThatThrownBy(() -> detector.detect(dir, "latin-nine-and-a-half"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("latin-nine-and-a-half");
    }

    @Test
    void theUsualBuildSourceEncodingPropertyIsRead() throws IOException {
        writePom(dir, "<properties><project.build.sourceEncoding>ISO-8859-1</project.build.sourceEncoding></properties>");

        DetectedSourceEncoding detected = detector.detect(dir, null);

        assertThat(detected.charset()).isEqualTo(StandardCharsets.ISO_8859_1);
        assertThat(detected.source()).contains("project.build.sourceEncoding").contains("ISO-8859-1");
    }

    @Test
    void theCompilerPluginsOwnEncodingBeatsTheCompilerPropertyWhichBeatsTheBuildProperty() throws IOException {
        writePom(dir, "<properties><project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>"
                + "<maven.compiler.encoding>ISO-8859-15</maven.compiler.encoding></properties>"
                + "<build><plugins><plugin><artifactId>maven-compiler-plugin</artifactId>"
                + "<configuration><encoding>windows-1252</encoding></configuration></plugin></plugins></build>");

        assertThat(detector.detect(dir, null).charset().name()).isEqualTo("windows-1252");

        writePom(dir, "<properties><project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>"
                + "<maven.compiler.encoding>ISO-8859-15</maven.compiler.encoding></properties>");

        assertThat(detector.detect(dir, null).charset().name()).isEqualTo("ISO-8859-15");
    }

    @Test
    void aPropertyReferenceInTheValueIsResolved() throws IOException {
        writePom(dir, "<properties><my.encoding>windows-1252</my.encoding>"
                + "<project.build.sourceEncoding>${my.encoding}</project.build.sourceEncoding></properties>");

        assertThat(detector.detect(dir, null).charset().name()).isEqualTo("windows-1252");
    }

    @Test
    void anEncodingInheritedFromTheParentPomOnDiskIsUsed() throws IOException {
        writePom(dir, "<groupId>com.acme</groupId><artifactId>parent</artifactId><version>1</version>"
                + "<packaging>pom</packaging><properties>"
                + "<project.build.sourceEncoding>ISO-8859-1</project.build.sourceEncoding></properties>");
        Path module = dir.resolve("module");
        writePom(module, "<parent><groupId>com.acme</groupId><artifactId>parent</artifactId><version>1</version>"
                + "</parent><artifactId>module</artifactId>");

        assertThat(detector.detect(module, null).charset()).isEqualTo(StandardCharsets.ISO_8859_1);
    }

    @Test
    void aValueThatIsNotACharsetInThePomFallsThroughToTheNextSourceInsteadOfFailing() throws IOException {
        writePom(dir, "<properties><maven.compiler.encoding>not-a-charset</maven.compiler.encoding>"
                + "<project.build.sourceEncoding>ISO-8859-1</project.build.sourceEncoding></properties>");

        assertThat(detector.detect(dir, null).charset()).isEqualTo(StandardCharsets.ISO_8859_1);
    }

    @Test
    void nothingDeclaredMeansUtf8AndSaysItWasAnAssumption() throws IOException {
        writePom(dir, "<artifactId>module</artifactId>");

        DetectedSourceEncoding detected = detector.detect(dir, null);

        assertThat(detected.charset()).isEqualTo(StandardCharsets.UTF_8);
        assertThat(detected.declared()).isFalse();
        assertThat(detected.source()).contains("assuming UTF-8");
    }

    @Test
    void aModuleWithNoPomAtAllAlsoFallsBackToUtf8() {
        assertThat(detector.detect(dir.resolve("nowhere"), null).charset()).isEqualTo(StandardCharsets.UTF_8);
    }

    private static void writePom(Path directory, String content) throws IOException {
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("pom.xml"),
                "<project xmlns=\"http://maven.apache.org/POM/4.0.0\"><modelVersion>4.0.0</modelVersion>"
                        + content + "</project>");
    }
}
