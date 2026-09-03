package com.devmanchego.jtestforge.spring;

import com.devmanchego.jtestforge.config.SpringConfig;
import com.devmanchego.jtestforge.config.SpringEnabledMode;
import com.devmanchego.jtestforge.config.TiersConfig;
import com.devmanchego.jtestforge.model.ModuleDependency;
import com.devmanchego.jtestforge.model.SpringStackFacts;
import com.devmanchego.jtestforge.model.SpringTierState;
import com.devmanchego.jtestforge.model.Tier;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SpringStackDetectorTest {

    private final SpringStackDetector detector = new SpringStackDetector();

    @Test
    void aModuleWithNoSpringAtAllYieldsNoSpringFacts() {
        SpringStackFacts facts = detector.detect(List.of(
                dependency("org.junit.jupiter", "junit-jupiter", "5.10.3", "test")));

        assertThat(facts.springTestPresent()).isFalse();
        assertThat(facts.bootTestPresent()).isFalse();
        assertThat(facts.hasEmbeddedDatabase()).isFalse();
    }

    @Test
    void springFrameworkSixTwoSelectsTheMockitoBeanAnnotation() {
        SpringStackFacts facts = detector.detect(bootStack("3.4.1", "6.2.1"));

        assertThat(facts.mockBeanAnnotationFqn()).isEqualTo("org.springframework.test.context.bean.override.mockito.MockitoBean");
        assertThat(facts.mockMvcTesterAvailable()).isTrue();
    }

    @Test
    void belowSpringFrameworkSixTwoTheOlderMockBeanAnnotationIsSelected() {
        SpringStackFacts facts = detector.detect(bootStack("3.3.5", "6.1.14"));

        assertThat(facts.mockBeanAnnotationFqn()).isEqualTo("org.springframework.boot.test.mock.mockito.MockBean");
        assertThat(facts.mockMvcTesterAvailable()).isFalse();
    }

    @Test
    void aPreReleaseOfSixTwoStillCountsAsSixTwo() {
        // 6.2.0-RC1 genuinely has @MockitoBean; treating it as older would generate the
        // wrong annotation and fail to compile.
        SpringStackFacts facts = detector.detect(bootStack("3.4.0-RC1", "6.2.0-RC1"));

        assertThat(facts.mockBeanAnnotationFqn()).contains("MockitoBean");
    }

    @Test
    void embeddedDatabasesAreDetectedOnTheTestClasspathOnly() {
        List<ModuleDependency> withH2 = new ArrayList<>(bootStack("3.4.1", "6.2.1"));
        withH2.add(dependency("com.h2database", "h2", "2.2.224", "test"));

        assertThat(detector.detect(withH2).embeddedDatabase())
                .isEqualTo(SpringStackFacts.DetectedEmbeddedDatabase.H2);
    }

    @Test
    void aDatabaseAtCompileScopeIsNotTreatedAsATestDatabase() {
        List<ModuleDependency> compileScopedH2 = new ArrayList<>(bootStack("3.4.1", "6.2.1"));
        compileScopedH2.add(dependency("com.h2database", "h2", "2.2.224", "compile"));

        // A production dependency on H2 says nothing about what @DataJpaTest can run
        // against; only test-scoped availability makes the tier viable.
        assertThat(detector.detect(compileScopedH2).hasEmbeddedDatabase()).isFalse();
    }

    @Test
    void theValidationApiFlavourIsDetected() {
        List<ModuleDependency> jakarta = new ArrayList<>(bootStack("3.4.1", "6.2.1"));
        jakarta.add(dependency("jakarta.validation", "jakarta.validation-api", "3.0.2", "compile"));

        assertThat(detector.detect(jakarta).validationApi())
                .isEqualTo(SpringStackFacts.ValidationApi.JAKARTA);
    }

    @Test
    void theWebSliceTierRequiresBothBootTestSupportAndSpringMvc() {
        SpringStackFacts withoutWeb = detector.detect(bootStack("3.4.1", "6.2.1"));

        SpringTierState state = detector.resolveTierAvailability(withoutWeb, allTiersEnabled());

        assertThat(state.isAvailable(Tier.WEB_SLICE)).isFalse();
        assertThat(state.unavailable().get(Tier.WEB_SLICE)).containsIgnoringCase("spring mvc");
    }

    @Test
    void theWebSliceTierIsAvailableWithBootTestSupportAndSpringMvc() {
        List<ModuleDependency> withWeb = new ArrayList<>(bootStack("3.4.1", "6.2.1"));
        withWeb.add(dependency("org.springframework.boot", "spring-boot-starter-web", "3.4.1", "compile"));

        SpringTierState state = detector.resolveTierAvailability(
                detector.detect(withWeb), allTiersEnabled());

        assertThat(state.isAvailable(Tier.WEB_SLICE)).isTrue();
    }

    @Test
    void theDataSliceTierIsUnavailableWithNoEmbeddedDatabaseAndSaysSo() {
        List<ModuleDependency> jpaButNoDatabase = new ArrayList<>(bootStack("3.4.1", "6.2.1"));
        jpaButNoDatabase.add(dependency("org.springframework.boot", "spring-boot-starter-data-jpa", "3.4.1", "compile"));

        SpringTierState state = detector.resolveTierAvailability(
                detector.detect(jpaButNoDatabase), allTiersEnabled());

        assertThat(state.isAvailable(Tier.DATA_SLICE)).isFalse();
        assertThat(state.unavailable().get(Tier.DATA_SLICE)).containsIgnoringCase("embedded database");
    }

    @Test
    void theDataSliceTierIsAvailableWithJpaAndATestScopedDatabase() {
        List<ModuleDependency> full = new ArrayList<>(bootStack("3.4.1", "6.2.1"));
        full.add(dependency("org.springframework.boot", "spring-boot-starter-data-jpa", "3.4.1", "compile"));
        full.add(dependency("com.h2database", "h2", "2.2.224", "test"));

        SpringTierState state = detector.resolveTierAvailability(
                detector.detect(full), allTiersEnabled());

        assertThat(state.isAvailable(Tier.DATA_SLICE)).isTrue();
    }

    @Test
    void sliceTiersRequireSpringBootTestNotMerelySpringTest() {
        // @WebMvcTest, @DataJpaTest, @JsonTest and @SpringBootTest are all Spring Boot
        // Test annotations. A plain (non-Boot) Spring project has spring-test - and none
        // of them - so generating a slice there would fail to compile every time.
        List<ModuleDependency> plainSpringOnly = List.of(
                dependency("org.springframework", "spring-core", "6.2.1", "compile"),
                dependency("org.springframework", "spring-webmvc", "6.2.1", "compile"),
                dependency("org.springframework", "spring-test", "6.2.1", "test"));

        SpringStackFacts facts = detector.detect(plainSpringOnly);
        SpringTierState state = detector.resolveTierAvailability(facts, allTiersEnabled());

        assertThat(facts.springTestPresent()).isTrue();
        assertThat(facts.bootTestPresent()).isFalse();
        assertThat(state.isAvailable(Tier.WEB_SLICE)).isFalse();
        assertThat(state.unavailable().get(Tier.WEB_SLICE)).containsIgnoringCase("spring boot test");
    }

    @Test
    void aTierDisabledInConfigurationIsReportedUnavailableWithThatAsTheReason() {
        List<ModuleDependency> full = new ArrayList<>(bootStack("3.4.1", "6.2.1"));
        full.add(dependency("org.springframework.boot", "spring-boot-starter-web", "3.4.1", "compile"));

        SpringTierState state = detector.resolveTierAvailability(detector.detect(full), defaultTiers());

        // contextSlice defaults to false (§1.1), so T4 must be unavailable by choice.
        assertThat(state.isAvailable(Tier.CONTEXT_SLICE)).isFalse();
        assertThat(state.unavailable().get(Tier.CONTEXT_SLICE)).containsIgnoringCase("disabled");
    }

    @Test
    void thePlainUnitTierIsAlwaysAvailableEvenWithNoSpringAtAll() {
        SpringTierState state = detector.resolveTierAvailability(
                SpringStackFacts.noSpring(), allTiersEnabled());

        assertThat(state.isAvailable(Tier.PLAIN_UNIT)).isTrue();
        assertThat(state.unavailable()).doesNotContainKey(Tier.PLAIN_UNIT);
    }

    @Test
    void theContextLoadBudgetComesFromConfiguration() {
        SpringTierState state = detector.resolveTierAvailability(
                SpringStackFacts.noSpring(), allTiersEnabled());

        assertThat(state.contextLoadBudget()).isEqualTo(40);
        assertThat(state.contextLoads()).isZero();
    }

    // --- fixtures -----------------------------------------------------------------

    private List<ModuleDependency> bootStack(String bootVersion, String frameworkVersion) {
        return new ArrayList<>(List.of(
                dependency("org.springframework.boot", "spring-boot", bootVersion, "compile"),
                dependency("org.springframework.boot", "spring-boot-starter-test", bootVersion, "test"),
                dependency("org.springframework", "spring-core", frameworkVersion, "compile"),
                dependency("org.springframework", "spring-test", frameworkVersion, "test")));
    }

    private ModuleDependency dependency(String groupId, String artifactId, String version, String scope) {
        return new ModuleDependency(groupId, artifactId, version, scope);
    }

    private SpringConfig allTiersEnabled() {
        return new SpringConfig(SpringEnabledMode.AUTO,
                new TiersConfig(true, true, true, true, true),
                null, null, null, null, null, null, null, null, null);
    }

    private SpringConfig defaultTiers() {
        return new SpringConfig(SpringEnabledMode.AUTO, null,
                null, null, null, null, null, null, null, null, null);
    }
}
