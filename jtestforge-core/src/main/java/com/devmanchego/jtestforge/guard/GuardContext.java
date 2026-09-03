package com.devmanchego.jtestforge.guard;

import com.devmanchego.jtestforge.model.ProductionMethod;
import com.devmanchego.jtestforge.model.SemanticGap;
import com.devmanchego.jtestforge.model.TestClassInfo;
import com.devmanchego.jtestforge.model.Tier;

import java.util.List;
import java.util.Objects;

/**
 * Everything the guard battery needs beyond the candidate itself.
 *
 * <p>Assembled by the pass engine, which is the only component that has all of it to
 * hand. Keeping it in one record means the guards themselves stay pure functions of
 * (candidate, context) and can each be tested against a plain fixture.
 *
 * @param testClass    the class the candidate would be merged into, or {@code null} when
 *                     the class is about to be created
 * @param targetMethod the production method under test - its return type decides whether
 *                     a verify-only test is legitimate (§11.1 guard 2) and whether a
 *                     bare-status assertion is acceptable (guard 8)
 * @param tier         the unit's tier; the Spring guards apply only above PLAIN_UNIT
 * @param gaps         the framework-semantic gaps this candidate was generated to close,
 *                     empty for a coverage-driven or mutation-driven unit
 */
public record GuardContext(
        TestClassInfo testClass,
        ProductionMethod targetMethod,
        Tier tier,
        List<SemanticGap> gaps) {

    public GuardContext {
        Objects.requireNonNull(tier, "tier");
        gaps = gaps == null ? List.of() : List.copyOf(gaps);
    }

    public boolean isSpringTier() {
        return tier != Tier.PLAIN_UNIT;
    }
}
