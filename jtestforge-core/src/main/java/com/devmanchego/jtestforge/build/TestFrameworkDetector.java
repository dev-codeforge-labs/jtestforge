package com.devmanchego.jtestforge.build;

import com.devmanchego.jtestforge.model.ModuleDependency;
import com.devmanchego.jtestforge.model.SemanticVersion;
import com.devmanchego.jtestforge.model.TestFrameworkVersions;

import java.util.List;
import java.util.Optional;

/**
 * Reads the test-framework versions out of a parsed {@code mvn dependency:list} —
 * jtestforge-specification.md §7.3. The non-Spring half of the same pass
 * {@code SpringStackDetector} performs.
 */
public final class TestFrameworkDetector {

    public TestFrameworkVersions detect(List<ModuleDependency> dependencies) {
        return new TestFrameworkVersions(
                versionOf(dependencies, "junit-jupiter-api")
                        .or(() -> versionOf(dependencies, "junit-jupiter")).orElse(null),
                versionOf(dependencies, "mockito-core").orElse(null),
                hasArtifact(dependencies, "mockito-junit-jupiter"),
                hasArtifact(dependencies, "mockito-inline"),
                versionOf(dependencies, "assertj-core").orElse(null),
                versionOf(dependencies, "hamcrest")
                        .or(() -> versionOf(dependencies, "hamcrest-core")).orElse(null));
    }

    private boolean hasArtifact(List<ModuleDependency> dependencies, String artifactId) {
        return dependencies.stream().anyMatch(dependency -> dependency.hasArtifactId(artifactId));
    }

    private Optional<SemanticVersion> versionOf(List<ModuleDependency> dependencies, String artifactId) {
        return dependencies.stream()
                .filter(dependency -> dependency.hasArtifactId(artifactId))
                .findFirst()
                .flatMap(dependency -> SemanticVersion.parse(dependency.version()));
    }
}
