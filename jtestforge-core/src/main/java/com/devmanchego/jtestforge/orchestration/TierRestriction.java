package com.devmanchego.jtestforge.orchestration;

import com.devmanchego.jtestforge.model.Tier;

import java.util.EnumSet;
import java.util.Set;

/**
 * The {@code --tier} / {@code --no-spring} restriction for one {@code generate} invocation
 * — jtestforge-specification.md §14.
 *
 * <p>Deliberately a per-invocation filter, not a change to unit discovery or to the
 * persisted state: a unit excluded this run is left exactly as it was found (still
 * {@code PENDING}) and is picked up normally the next time the tool runs without the
 * restriction.
 */
public final class TierRestriction {

    private final Set<Tier> allowed;

    private TierRestriction(Set<Tier> allowed) {
        this.allowed = allowed;
    }

    /** No restriction: every tier the module makes available may run. */
    public static TierRestriction allTiers() {
        return new TierRestriction(EnumSet.allOf(Tier.class));
    }

    /** {@code --tier} (repeatable): only these tiers may run. */
    public static TierRestriction only(Set<Tier> tiers) {
        if (tiers.isEmpty()) {
            throw new IllegalArgumentException("--tier requires at least one tier");
        }
        return new TierRestriction(EnumSet.copyOf(tiers));
    }

    /** {@code --no-spring}: a fast run that loads no context. */
    public static TierRestriction noSpring() {
        return only(Set.of(Tier.PLAIN_UNIT));
    }

    public boolean allows(Tier tier) {
        return allowed.contains(tier);
    }
}
