package com.devmanchego.jtestforge.orchestration;

import com.devmanchego.jtestforge.model.Tier;
import com.devmanchego.jtestforge.model.WorkUnit;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Orders pending units for execution — jtestforge-specification.md §9.6.
 *
 * <p>Two rules, both cost-driven and both cheap to get right with a stable sort:
 *
 * <ul>
 *   <li><b>Tier-ordered execution.</b> Every unit at a cheaper tier runs before the first
 *       unit at a more expensive one, module-wide. {@link Tier}'s declared order is
 *       already cheapest-first, so this is a sort key, not a policy decision made here.
 *       An interrupted run has therefore always delivered its cheap value first.</li>
 *   <li><b>Class-grouped slice execution.</b> Within one tier, every unit belonging to the
 *       same test class runs consecutively, in the order that class was first
 *       encountered. This is what lets one controller's units share one cached context
 *       instead of the run bouncing between classes and re-triggering the escalation and
 *       repair machinery on a class it had already finished with.</li>
 * </ul>
 *
 * <p>Both rules are stable: units are never reordered relative to each other beyond what
 * the two rules require, so a fixture with no tier or class variation keeps discovery
 * order exactly.
 */
final class TierScheduler {

    private TierScheduler() {
    }

    static List<WorkUnit> order(List<WorkUnit> units) {
        Map<Tier, Map<String, List<WorkUnit>>> byTierThenClass = new LinkedHashMap<>();
        for (WorkUnit unit : units) {
            byTierThenClass
                    .computeIfAbsent(unit.tier(), tier -> new LinkedHashMap<>())
                    .computeIfAbsent(unit.testFile(), testFile -> new ArrayList<>())
                    .add(unit);
        }

        List<WorkUnit> ordered = new ArrayList<>(units.size());
        for (Tier tier : Tier.values()) {
            Map<String, List<WorkUnit>> byClass = byTierThenClass.get(tier);
            if (byClass == null) {
                continue;
            }
            byClass.values().forEach(ordered::addAll);
        }
        return ordered;
    }
}
