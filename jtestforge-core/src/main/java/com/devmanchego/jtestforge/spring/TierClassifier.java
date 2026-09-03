package com.devmanchego.jtestforge.spring;

import com.devmanchego.jtestforge.model.ProductionClass;
import com.devmanchego.jtestforge.model.SpringStereotype;
import com.devmanchego.jtestforge.model.Tier;

/**
 * Assigns each production class its Spring stereotype and the tier its own units are
 * generated at — jtestforge-specification.md §7.4. Deterministic, and decided before any
 * prompt is rendered.
 */
public final class TierClassifier {

    public SpringStereotype classify(ProductionClass productionClass) {
        if (productionClass.hasAnyAnnotation(SpringAnnotations.CONTROLLER_ADVICE)) {
            return SpringStereotype.CONTROLLER_ADVICE;
        }
        if (productionClass.hasAnyAnnotation(SpringAnnotations.CONTROLLER)) {
            return SpringStereotype.CONTROLLER;
        }
        if (productionClass.hasAnyAnnotation(SpringAnnotations.REPOSITORY) || isSpringDataRepository(productionClass)) {
            return SpringStereotype.REPOSITORY;
        }
        if (productionClass.hasAnyAnnotation(SpringAnnotations.CONFIGURATION_PROPERTIES)) {
            // Checked before @Configuration: a properties class is frequently annotated
            // with both, and its binding contract is the more specific, cheaper thing to
            // verify.
            return SpringStereotype.CONFIGURATION_PROPERTIES;
        }
        if (productionClass.hasAnyAnnotation(SpringAnnotations.CONFIGURATION)) {
            return SpringStereotype.CONFIGURATION;
        }
        if (productionClass.hasAnyAnnotation(SpringAnnotations.JSON_COMPONENT)) {
            return SpringStereotype.JSON_COMPONENT;
        }
        if (productionClass.hasAnyAnnotation(SpringAnnotations.PLAIN_BEAN)) {
            return SpringStereotype.PLAIN_BEAN;
        }
        return SpringStereotype.NONE;
    }

    /**
     * The tier this class's own work units are generated at.
     *
     * <p>With {@code preferLowestTier} (the default), everything that <em>can</em> be
     * unit-tested is unit-tested: a controller's own branching is ordinary Java, and its
     * mapping contract arrives separately as {@code WEB_SLICE} units derived from the
     * semantic gaps (§7.5). That split keeps the expensive tier scoped to what only it can
     * prove, and keeps the mutation pass - restricted to T0 by default (§10.3) - fed with
     * fast tests.
     *
     * <p>A Spring Data repository interface is the one case that ignores the preference:
     * its derived queries have no bodies, so there is nothing for a unit test to call.
     */
    public Tier primaryTier(ProductionClass productionClass, boolean preferLowestTier) {
        if (isSpringDataRepository(productionClass)) {
            return Tier.DATA_SLICE;
        }
        if (preferLowestTier) {
            return Tier.PLAIN_UNIT;
        }
        return naturalTierOf(classify(productionClass));
    }

    /** The tier a stereotype maps to when the lowest-tier preference is switched off. */
    public Tier naturalTierOf(SpringStereotype stereotype) {
        return switch (stereotype) {
            case CONTROLLER, CONTROLLER_ADVICE -> Tier.WEB_SLICE;
            case REPOSITORY -> Tier.DATA_SLICE;
            case CONFIGURATION_PROPERTIES, JSON_COMPONENT -> Tier.JSON_SLICE;
            case CONFIGURATION -> Tier.CONTEXT_SLICE;
            case PLAIN_BEAN, NONE -> Tier.PLAIN_UNIT;
        };
    }

    private boolean isSpringDataRepository(ProductionClass productionClass) {
        return productionClass.extendsAnyOf(SpringAnnotations.DATA_REPOSITORY_SUPERTYPES);
    }
}
