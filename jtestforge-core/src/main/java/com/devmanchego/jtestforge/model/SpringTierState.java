package com.devmanchego.jtestforge.model;

import java.util.Map;
import java.util.Set;

/**
 * Which Spring tiers this run can actually use, and how much of the context-load budget
 * it has spent — jtestforge-specification.md §7.3, §7.6, §9.6.
 *
 * <p>{@code contextLoads} is tracked in the persisted state rather than only in memory
 * because it is the cheap early warning that the context cache key was forked (§7.6): a
 * count that climbs faster than one per test class means a generated test changed the
 * key, and that is a cost the user's CI would otherwise pay silently, forever.
 *
 * @param available          tiers whose prerequisites were all detected
 * @param unavailable        tiers that cannot run, each with the missing prerequisite
 * @param contextLoads       ApplicationContext loads consumed so far
 * @param contextLoadBudget  the run's ceiling, from {@code spring.maxContextLoadsPerRun}
 */
public record SpringTierState(
        Set<Tier> available,
        Map<Tier, String> unavailable,
        int contextLoads,
        int contextLoadBudget) {

    public SpringTierState {
        available = available == null ? Set.of() : Set.copyOf(available);
        // Not EnumMap: its Map-taking constructor cannot infer the enum type from an
        // empty map and throws. An empty unavailable map is the normal case for a module
        // where every tier is viable.
        unavailable = unavailable == null ? Map.of() : Map.copyOf(unavailable);
    }

    /** State for a run with no Spring support at all: only the plain unit tier runs. */
    public static SpringTierState springDisabled(int contextLoadBudget) {
        return new SpringTierState(Set.of(Tier.PLAIN_UNIT), Map.of(), 0, contextLoadBudget);
    }

    public boolean isAvailable(Tier tier) {
        return available.contains(tier);
    }

    public boolean budgetExhausted() {
        return contextLoads >= contextLoadBudget;
    }

    public SpringTierState withContextLoads(int newContextLoads) {
        return new SpringTierState(available, unavailable, newContextLoads, contextLoadBudget);
    }
}
