package com.devmanchego.jtestforge.orchestration;

import com.devmanchego.jtestforge.model.ClassCoverage;
import com.devmanchego.jtestforge.model.ProductionClass;
import com.devmanchego.jtestforge.model.SpringStackFacts;
import com.devmanchego.jtestforge.model.SpringTierState;
import com.devmanchego.jtestforge.model.TestFrameworkVersions;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Everything {@link WorkUnitDiscovery} needs, gathered once by the caller —
 * jtestforge-specification.md §9 step 3's "cross the AST scan with the coverage snapshot
 * and the framework-semantic gap scan".
 *
 * <p>A value rather than five constructor parameters because all of it is produced by one
 * preflight pass over the module and consumed together: re-deriving any of it per class
 * would repeat a source scan, a Maven invocation or a JaCoCo parse that has already run.
 *
 * @param productionClasses  every class the AST scan found, in scan order
 * @param coverageByClassFqn the baseline coverage snapshot, keyed by production class FQN;
 *                           a class absent from it has no measured coverage at all, which
 *                           is materially different from having zero
 */
public record DiscoveryInputs(
        List<ProductionClass> productionClasses,
        Map<String, ClassCoverage> coverageByClassFqn,
        SpringTierState springTiers,
        SpringStackFacts springFacts,
        TestFrameworkVersions frameworkVersions) {

    public DiscoveryInputs {
        productionClasses = productionClasses == null ? List.of() : List.copyOf(productionClasses);
        coverageByClassFqn = coverageByClassFqn == null ? Map.of() : Map.copyOf(coverageByClassFqn);
        Objects.requireNonNull(springTiers, "springTiers");
        springFacts = springFacts == null ? SpringStackFacts.noSpring() : springFacts;
        frameworkVersions = frameworkVersions == null ? TestFrameworkVersions.none() : frameworkVersions;
    }
}
