package com.devmanchego.jtestforge.spring;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Tracks which test classes have already had their mock-bean set recomputed —
 * jtestforge-specification.md §7.6.
 *
 * <p>An escalation is allowed <b>once per test class</b>. Re-synthesising the class
 * invalidates its cached context, so an unbounded escalation path would let a single
 * uncooperative class reload the context on every attempt - exactly the runaway cost the
 * context-load budget exists to catch, arrived at by a route the budget would only notice
 * after the damage was done.
 */
public final class MockBeanEscalation {

    private final Set<String> escalatedTestClasses = new LinkedHashSet<>();

    /**
     * @return {@code true} if this class may escalate now, {@code false} if it already
     *         has and the unit must be failed instead
     */
    public boolean recordEscalation(String testClassFqn) {
        return escalatedTestClasses.add(testClassFqn);
    }

    public boolean hasAlreadyEscalated(String testClassFqn) {
        return escalatedTestClasses.contains(testClassFqn);
    }
}
