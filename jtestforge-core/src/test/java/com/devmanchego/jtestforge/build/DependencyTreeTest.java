package com.devmanchego.jtestforge.build;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The reactor resource is this project's own {@code mvn test-compile dependency:tree
 * -DoutputFile=... -DappendOutput=true}, captured verbatim - real plugin output, not a
 * hand-written approximation of it.
 */
class DependencyTreeTest {

    @Test
    void aWholeReactorTreeSplitsIntoOneBlockPerModule() throws URISyntaxException {
        DependencyTree tree = DependencyTree.read(resource("dependency-tree/reactor-tree.txt"));

        assertThat(tree.moduleKeys()).containsExactly(
                "com.devmanchego.jtestforge:jtestforge-parent",
                "com.devmanchego.jtestforge:jtestforge-core",
                "com.devmanchego.jtestforge:jtestforge-cli");
        assertThat(tree.module("com.devmanchego.jtestforge", "jtestforge-parent")).hasValue(List.of());
        assertThat(tree.module("com.devmanchego.jtestforge", "jtestforge-core").orElseThrow())
                .extracting(DependencyTreeEntry::coordinate)
                .contains("com.github.javaparser:javaparser-core:jar:3.26.2:compile",
                        "ch.qos.logback:logback-core:jar:1.5.6:runtime",
                        "net.bytebuddy:byte-buddy:jar:1.14.18:test")
                .doesNotContain("info.picocli:picocli:jar:4.7.6:compile");
        assertThat(tree.module("com.devmanchego.jtestforge", "jtestforge-cli").orElseThrow())
                .extracting(DependencyTreeEntry::coordinate)
                .contains("com.devmanchego.jtestforge:jtestforge-core:jar:1.0.0-SNAPSHOT:compile",
                        "com.github.javaparser:javaparser-core:jar:3.26.2:compile",
                        "info.picocli:picocli:jar:4.7.6:compile");
    }

    @Test
    void aCapturedConsoleLogIsReadDespiteTheLogPrefixAndReactorNoise() {
        DependencyTree tree = DependencyTree.parse("""
                [INFO] Scanning for projects...
                [INFO] ------------------------< com.acme:shop >------------------------
                [INFO] Building shop 1.0                                          [1/1]
                [INFO] --- dependency:3.6.1:tree (default-cli) @ shop ---
                [WARNING] Parameter 'localRepository' is deprecated
                [INFO] com.acme:shop:jar:1.0
                [INFO] +- org.slf4j:slf4j-api:jar:1.7.36:compile
                [INFO] \\- junit:junit:jar:4.13.2:test
                [INFO]    \\- org.hamcrest:hamcrest-core:jar:1.3:test
                [INFO] ------------------------------------------------------------------------
                [INFO] BUILD SUCCESS
                """);

        assertThat(tree.moduleKeys()).containsExactly("com.acme:shop");
        assertThat(tree.module("com.acme", "shop").orElseThrow())
                .extracting(DependencyTreeEntry::artifactId)
                .containsExactly("slf4j-api", "junit", "hamcrest-core");
    }

    @Test
    void classifiersAndAnnotationsAreReadAndVerboseOmissionsAreDropped() {
        DependencyTree tree = DependencyTree.parse("""
                com.acme:shop:war:1.0
                +- com.acme:shared:jar:tests:1.0:test
                +- org.hibernate:hibernate-core:jar:5.6.15.Final:compile (optional)
                |  \\- (org.jboss.logging:jboss-logging:jar:3.4.3.Final:compile - omitted for duplicate)
                \\- com.oracle:ojdbc8:jar:19.3:system
                """);

        assertThat(tree.module("com.acme", "shop").orElseThrow())
                .extracting(DependencyTreeEntry::coordinate)
                .containsExactly(
                        "com.acme:shared:jar:tests:1.0:test",
                        "org.hibernate:hibernate-core:jar:5.6.15.Final:compile",
                        "com.oracle:ojdbc8:jar:19.3:system");
    }

    @Test
    void colouredOutputIsReadAsPlainText() {
        DependencyTree tree = DependencyTree.parse(
                "[[1;34mINFO[m] [1mcom.acme:shop:jar:1.0[m\n"
                        + "[[1;34mINFO[m] \\- org.slf4j:slf4j-api:jar:1.7.36:compile\n");

        assertThat(tree.module("com.acme", "shop").orElseThrow())
                .extracting(DependencyTreeEntry::artifactId).containsExactly("slf4j-api");
    }

    @Test
    void aTreeSavedByWindowsPowerShellAsUtf16IsDecoded(@TempDir Path dir) throws IOException {
        byte[] body = "com.acme:shop:jar:1.0\r\n\\- org.slf4j:slf4j-api:jar:1.7.36:compile\r\n"
                .getBytes(StandardCharsets.UTF_16LE);
        byte[] withBom = new byte[body.length + 2];
        withBom[0] = (byte) 0xFF;
        withBom[1] = (byte) 0xFE;
        System.arraycopy(body, 0, withBom, 2, body.length);
        Path file = dir.resolve("deps-tree.txt");
        Files.write(file, withBom);

        assertThat(DependencyTree.read(file).module("com.acme", "shop").orElseThrow())
                .extracting(DependencyTreeEntry::artifactId).containsExactly("slf4j-api");
    }

    @Test
    void aModuleTheTreeDoesNotCoverIsAbsentRatherThanEmpty() {
        DependencyTree tree = DependencyTree.parse("com.acme:shop:jar:1.0\n");

        assertThat(tree.module("com.acme", "shop")).hasValue(List.of());
        assertThat(tree.module("com.acme", "billing")).isEmpty();
    }

    private Path resource(String name) throws URISyntaxException {
        return Path.of(Objects.requireNonNull(getClass().getClassLoader().getResource(name)).toURI());
    }
}
