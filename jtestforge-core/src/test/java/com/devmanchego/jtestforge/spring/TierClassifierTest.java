package com.devmanchego.jtestforge.spring;

import com.devmanchego.jtestforge.model.ProductionClass;
import com.devmanchego.jtestforge.model.ProductionMethod;
import com.devmanchego.jtestforge.model.SpringStereotype;
import com.devmanchego.jtestforge.model.Tier;
import com.devmanchego.jtestforge.model.Visibility;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TierClassifierTest {

    private final TierClassifier classifier = new TierClassifier();

    @Test
    void aRestControllerClassifiesAsAControllerStereotype() {
        assertThat(classifier.classify(classWith("org.springframework.web.bind.annotation.RestController")))
                .isEqualTo(SpringStereotype.CONTROLLER);
    }

    @Test
    void aControllerAdviceIsDistinguishedFromAPlainController() {
        assertThat(classifier.classify(classWith("org.springframework.web.bind.annotation.RestControllerAdvice")))
                .isEqualTo(SpringStereotype.CONTROLLER_ADVICE);
    }

    @Test
    void serviceAndComponentBothCollapseIntoPlainBean() {
        assertThat(classifier.classify(classWith("org.springframework.stereotype.Service")))
                .isEqualTo(SpringStereotype.PLAIN_BEAN);
        assertThat(classifier.classify(classWith("org.springframework.stereotype.Component")))
                .isEqualTo(SpringStereotype.PLAIN_BEAN);
    }

    @Test
    void aClassWithNoSpringAnnotationAtAllHasNoStereotype() {
        assertThat(classifier.classify(classWith())).isEqualTo(SpringStereotype.NONE);
    }

    @Test
    void configurationPropertiesIsDistinguishedFromConfiguration() {
        assertThat(classifier.classify(classWith("org.springframework.boot.context.properties.ConfigurationProperties")))
                .isEqualTo(SpringStereotype.CONFIGURATION_PROPERTIES);
        assertThat(classifier.classify(classWith("org.springframework.context.annotation.Configuration")))
                .isEqualTo(SpringStereotype.CONFIGURATION);
    }

    @Test
    void anAnnotationReachedThroughAWildcardImportIsStillRecognisedByItsSimpleName() {
        // AnnotationFqnResolver falls back to the bare name when a wildcard import left
        // no way to qualify it; classification must not silently miss the stereotype.
        assertThat(classifier.classify(classWith("RestController")))
                .isEqualTo(SpringStereotype.CONTROLLER);
    }

    @Test
    void withPreferLowestTierAServiceStaysAtPlainUnit() {
        ProductionClass service = classWith("org.springframework.stereotype.Service");

        assertThat(classifier.primaryTier(service, true)).isEqualTo(Tier.PLAIN_UNIT);
    }

    @Test
    void withPreferLowestTierAControllerAlsoStaysAtPlainUnitForItsOwnBranching() {
        // §7.4 escalation rule: a controller produces T0 units for the branching inside
        // its handler bodies, and separate T1 units for the mapping contract - the
        // latter come from the semantic gaps, not from the class's primary tier.
        ProductionClass controller = classWith("org.springframework.web.bind.annotation.RestController");

        assertThat(classifier.primaryTier(controller, true)).isEqualTo(Tier.PLAIN_UNIT);
    }

    @Test
    void withPreferLowestTierDisabledAControllerGoesStraightToTheWebSlice() {
        ProductionClass controller = classWith("org.springframework.web.bind.annotation.RestController");

        assertThat(classifier.primaryTier(controller, false)).isEqualTo(Tier.WEB_SLICE);
    }

    @Test
    void aRepositoryInterfaceWithNoMethodBodiesGoesToDataSliceRegardlessOfPreferLowestTier() {
        // §7.4: a derived query method has no body, so T0 is not merely inefficient -
        // there is nothing at all to unit-test.
        ProductionClass repository = new ProductionClass("com.acme.OrderRepository",
                Path.of("OrderRepository.java"), List.of(), List.of("JpaRepository"), List.of(),
                List.of(queryMethod("findByReference")));

        assertThat(classifier.primaryTier(repository, true)).isEqualTo(Tier.DATA_SLICE);
        assertThat(classifier.classify(repository)).isEqualTo(SpringStereotype.REPOSITORY);
    }

    @Test
    void aConfigurationClassGoesToTheContextSliceWhenNotPreferringTheLowestTier() {
        ProductionClass configuration = classWith("org.springframework.context.annotation.Configuration");

        assertThat(classifier.primaryTier(configuration, false)).isEqualTo(Tier.CONTEXT_SLICE);
    }

    // --- fixtures -----------------------------------------------------------------

    private ProductionClass classWith(String... annotations) {
        return new ProductionClass("com.acme.Subject", Path.of("Subject.java"),
                List.of(annotations), List.of(), List.of(),
                List.of(new ProductionMethod("doWork", "void", List.of(), Visibility.PUBLIC, false,
                        List.of(), Map.of(), List.of(), 10, 20, 2)));
    }

    private ProductionMethod queryMethod(String name) {
        return new ProductionMethod(name, "java.util.List", List.of(), Visibility.PUBLIC, false,
                List.of(), Map.of(), List.of(), 5, 5, Integer.MAX_VALUE);
    }
}
