package com.devmanchego.jtestforge.orchestration;

import com.devmanchego.jtestforge.model.Tier;

import java.util.Objects;

/**
 * One (production class, test class) pair the module actually has, tagged with the tier
 * its test class was generated at — jtestforge-specification.md §10.3.
 *
 * <p>What {@link HardenEngine} needs to build the baseline PIT run's {@code targetClasses}
 * / {@code targetTests}, restricted to {@code harden.maxTierForMutation}. The full
 * inventory of a module's classes and their tiers is a product of pass 1's own discovery
 * (§9 step 3, {@code TierClassifier}); {@code HardenEngine} does not scan for it itself -
 * it only applies the restriction to what it is handed.
 *
 * @param productionClassFqn the mutated class PIT would target
 * @param testClassFqn       the test class PIT would run against it
 * @param tier                the tier that test class was generated at
 */
public record TierScopedTestClass(String productionClassFqn, String testClassFqn, Tier tier) {

    public TierScopedTestClass {
        Objects.requireNonNull(productionClassFqn, "productionClassFqn");
        Objects.requireNonNull(testClassFqn, "testClassFqn");
        Objects.requireNonNull(tier, "tier");
    }
}
