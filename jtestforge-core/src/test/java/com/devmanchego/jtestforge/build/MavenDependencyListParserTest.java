package com.devmanchego.jtestforge.build;

import com.devmanchego.jtestforge.model.ModuleDependency;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

class MavenDependencyListParserTest {

    @Test
    void parsesTheStandardFivePartCoordinate() {
        List<ModuleDependency> dependencies = MavenDependencyListParser.parse("""

                The following files have been resolved:
                   org.springframework:spring-core:jar:6.2.1:compile
                   com.h2database:h2:jar:2.2.224:test
                """);

        assertThat(dependencies)
                .extracting(ModuleDependency::groupId, ModuleDependency::artifactId,
                        ModuleDependency::version, ModuleDependency::scope)
                .containsExactly(
                        tuple("org.springframework", "spring-core", "6.2.1", "compile"),
                        tuple("com.h2database", "h2", "2.2.224", "test"));
    }

    @Test
    void parsesASixPartCoordinateThatCarriesAClassifier() {
        // Version and scope are read from the end precisely so the classifier case needs
        // no special detection.
        List<ModuleDependency> dependencies = MavenDependencyListParser.parse(
                "   org.example:native-lib:jar:linux-x86_64:1.4.0:runtime");

        assertThat(dependencies).singleElement()
                .satisfies(dependency -> {
                    assertThat(dependency.artifactId()).isEqualTo("native-lib");
                    assertThat(dependency.version()).isEqualTo("1.4.0");
                    assertThat(dependency.scope()).isEqualTo("runtime");
                });
    }

    @Test
    void stripsMavenLogPrefixesAndIgnoresHeadingLines() {
        List<ModuleDependency> dependencies = MavenDependencyListParser.parse("""
                [INFO] --- dependency:3.6.0:list (default-cli) @ demo ---
                [INFO]
                [INFO] The following files have been resolved:
                [INFO]    org.slf4j:slf4j-api:jar:2.0.16:compile
                [INFO] BUILD SUCCESS
                """);

        assertThat(dependencies).extracting(ModuleDependency::artifactId).containsExactly("slf4j-api");
    }

    @Test
    void ignoresTrailingModuleAnnotationsSomeMavenVersionsAppend() {
        List<ModuleDependency> dependencies = MavenDependencyListParser.parse(
                "   org.slf4j:slf4j-api:jar:2.0.16:compile -- module org.slf4j");

        assertThat(dependencies).singleElement()
                .satisfies(d -> assertThat(d.scope()).isEqualTo("compile"));
    }

    @Test
    void emptyOrNullOutputYieldsNoDependencies() {
        assertThat(MavenDependencyListParser.parse("")).isEmpty();
        assertThat(MavenDependencyListParser.parse(null)).isEmpty();
    }

    @Test
    void scopeIsNormalisedToLowercase() {
        List<ModuleDependency> dependencies = MavenDependencyListParser.parse(
                "   com.h2database:h2:jar:2.2.224:TEST");

        assertThat(dependencies).singleElement()
                .satisfies(d -> assertThat(d.scope()).isEqualTo("test"));
    }
}
