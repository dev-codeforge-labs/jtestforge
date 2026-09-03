package com.devmanchego.jtestforge.spring;

import com.devmanchego.jtestforge.config.SpringConfig;
import com.devmanchego.jtestforge.model.ModuleDependency;
import com.devmanchego.jtestforge.model.SemanticVersion;
import com.devmanchego.jtestforge.model.SpringStackFacts;
import com.devmanchego.jtestforge.model.SpringTierState;
import com.devmanchego.jtestforge.model.Tier;

import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Reads the target module's resolved dependency list and decides what its Spring stack
 * actually supports — jtestforge-specification.md §7.3.
 *
 * <p>Two outputs: the {@link SpringStackFacts} rendered into {@code {{SPRING_CONTEXT}}},
 * and the per-tier availability map, where every unavailable tier names the prerequisite
 * it is missing. JTestForge never edits the target {@code pom.xml} to make a tier viable
 * (§2), so "unavailable, and here is why" is the whole of the remedy this class offers.
 */
public final class SpringStackDetector {

    private static final String SPRING_GROUP = "org.springframework";
    private static final String BOOT_GROUP = "org.springframework.boot";

    /** {@code @MockitoBean} arrived in Spring Framework 6.2 / Boot 3.4. */
    private static final String MOCKITO_BEAN_FQN =
            "org.springframework.test.context.bean.override.mockito.MockitoBean";
    private static final String LEGACY_MOCK_BEAN_FQN =
            "org.springframework.boot.test.mock.mockito.MockBean";

    public SpringStackFacts detect(List<ModuleDependency> dependencies) {
        boolean springTestPresent = hasArtifact(dependencies, "spring-test")
                || hasArtifact(dependencies, "spring-boot-starter-test");
        boolean bootTestPresent = hasArtifact(dependencies, "spring-boot-starter-test")
                || hasArtifact(dependencies, "spring-boot-test")
                || hasArtifact(dependencies, "spring-boot-test-autoconfigure");

        SemanticVersion frameworkVersion = versionOf(dependencies, SPRING_GROUP, "spring-core")
                .or(() -> versionOf(dependencies, SPRING_GROUP, "spring-context"))
                .or(() -> versionOf(dependencies, SPRING_GROUP, "spring-test"))
                .orElse(null);
        SemanticVersion bootVersion = versionOf(dependencies, BOOT_GROUP, "spring-boot")
                .or(() -> versionOf(dependencies, BOOT_GROUP, "spring-boot-starter-test"))
                .orElse(null);

        return new SpringStackFacts(
                springTestPresent,
                bootTestPresent,
                frameworkVersion,
                bootVersion,
                hasArtifact(dependencies, "spring-webmvc")
                        || hasArtifact(dependencies, "spring-boot-starter-web"),
                hasArtifact(dependencies, "spring-data-jpa")
                        || hasArtifact(dependencies, "spring-boot-starter-data-jpa"),
                hasArtifact(dependencies, "spring-security-test"),
                detectEmbeddedDatabase(dependencies),
                detectValidationApi(dependencies),
                usesModernMockBean(frameworkVersion, bootVersion) ? MOCKITO_BEAN_FQN : LEGACY_MOCK_BEAN_FQN,
                bootVersion != null && bootVersion.isAtLeast(3, 4));
    }

    /**
     * Decides which tiers this run may use. A tier disabled in configuration is reported
     * as unavailable for that reason, rather than silently vanishing - the run report has
     * to be able to say why a class produced no slice test.
     */
    public SpringTierState resolveTierAvailability(SpringStackFacts facts, SpringConfig config) {
        Set<Tier> available = new LinkedHashSet<>();
        Map<Tier, String> unavailable = new EnumMap<>(Tier.class);

        for (Tier tier : Tier.values()) {
            Optional<String> missingPrerequisite = missingPrerequisiteFor(tier, facts, config);
            if (missingPrerequisite.isPresent()) {
                unavailable.put(tier, missingPrerequisite.get());
            } else {
                available.add(tier);
            }
        }
        return new SpringTierState(available, unavailable, 0, config.maxContextLoadsPerRun());
    }

    private Optional<String> missingPrerequisiteFor(Tier tier, SpringStackFacts facts, SpringConfig config) {
        if (!config.tiers().isEnabled(tier)) {
            return Optional.of("disabled in configuration (spring.tiers)");
        }
        if (tier == Tier.PLAIN_UNIT) {
            // Needs no Spring at all - it is a plain Mockito test.
            return Optional.empty();
        }
        if (!facts.springTestPresent()) {
            return Optional.of("no Spring test support on the module's test classpath");
        }
        if (!facts.bootTestPresent()) {
            // @WebMvcTest, @DataJpaTest, @JsonTest and @SpringBootTest are all Spring
            // Boot Test annotations, not plain Spring ones. Generating a slice against a
            // non-Boot Spring project would fail to compile every single time.
            return Optional.of("Spring Boot Test is not on the test classpath, "
                    + "and the slice annotations are Spring Boot's, not plain Spring's");
        }
        return switch (tier) {
            case WEB_SLICE -> facts.webMvcPresent()
                    ? Optional.empty()
                    : Optional.of("Spring MVC is not on the classpath");
            case DATA_SLICE -> missingDataSlicePrerequisite(facts, config);
            case JSON_SLICE, CONTEXT_SLICE -> Optional.empty();
            default -> Optional.empty();
        };
    }

    private Optional<String> missingDataSlicePrerequisite(SpringStackFacts facts, SpringConfig config) {
        if (!facts.dataJpaPresent()) {
            return Optional.of("Spring Data JPA is not on the classpath");
        }
        if (!facts.hasEmbeddedDatabase() && !config.allowTestcontainers()) {
            return Optional.of("no embedded database (H2, HSQLDB or Derby) on the test classpath");
        }
        return Optional.empty();
    }

    /**
     * The Framework version decides, since the annotation lives in {@code spring-test}.
     * Boot's version is the fallback for modules that pin Boot without exposing a
     * resolvable Framework artifact.
     */
    private boolean usesModernMockBean(SemanticVersion frameworkVersion, SemanticVersion bootVersion) {
        if (frameworkVersion != null) {
            return frameworkVersion.isAtLeast(6, 2);
        }
        return bootVersion != null && bootVersion.isAtLeast(3, 4);
    }

    private SpringStackFacts.DetectedEmbeddedDatabase detectEmbeddedDatabase(List<ModuleDependency> dependencies) {
        // Test scope only: a production dependency on H2 says nothing about what
        // @DataJpaTest can actually run against.
        if (hasTestScopedArtifact(dependencies, "h2")) {
            return SpringStackFacts.DetectedEmbeddedDatabase.H2;
        }
        if (hasTestScopedArtifact(dependencies, "hsqldb")) {
            return SpringStackFacts.DetectedEmbeddedDatabase.HSQLDB;
        }
        if (hasTestScopedArtifact(dependencies, "derby")) {
            return SpringStackFacts.DetectedEmbeddedDatabase.DERBY;
        }
        return SpringStackFacts.DetectedEmbeddedDatabase.NONE;
    }

    private SpringStackFacts.ValidationApi detectValidationApi(List<ModuleDependency> dependencies) {
        if (hasArtifact(dependencies, "jakarta.validation-api")) {
            return SpringStackFacts.ValidationApi.JAKARTA;
        }
        if (hasArtifact(dependencies, "validation-api")) {
            return SpringStackFacts.ValidationApi.JAVAX;
        }
        return SpringStackFacts.ValidationApi.NONE;
    }

    private boolean hasArtifact(List<ModuleDependency> dependencies, String artifactId) {
        return dependencies.stream().anyMatch(dependency -> dependency.hasArtifactId(artifactId));
    }

    private boolean hasTestScopedArtifact(List<ModuleDependency> dependencies, String artifactId) {
        return dependencies.stream()
                .anyMatch(dependency -> dependency.hasArtifactId(artifactId)
                        && "test".equals(dependency.scope()));
    }

    private Optional<SemanticVersion> versionOf(
            List<ModuleDependency> dependencies, String groupId, String artifactId) {
        return dependencies.stream()
                .filter(dependency -> dependency.is(groupId, artifactId))
                .findFirst()
                .flatMap(dependency -> SemanticVersion.parse(dependency.version()));
    }
}
