package com.devmanchego.jtestforge.spring;

import com.devmanchego.jtestforge.model.ContextKey;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Counts distinct Spring context loads against the run's budget —
 * jtestforge-specification.md §7.6, §9.6.
 *
 * <p>Counts <b>distinct context keys</b>, not test classes and certainly not test methods:
 * many classes sharing one key cost exactly one load, which is the whole point of keeping
 * the key stable. A count that climbs faster than one per key is the cheap early warning
 * that a generated test forked the cache.
 */
public final class ContextLoadCounter {

    private final int budget;
    private final Set<ContextKey> distinctKeys = new LinkedHashSet<>();

    public ContextLoadCounter(int budget) {
        this.budget = budget;
    }

    /**
     * Records that a test class with this key is about to run.
     *
     * @return whether this key was new, and therefore cost a real context load
     */
    public boolean record(ContextKey key) {
        return distinctKeys.add(key);
    }

    public int contextLoads() {
        return distinctKeys.size();
    }

    /** Exceeding the budget aborts the run with exit code 8 (spec 14.1). */
    public boolean budgetExhausted() {
        return distinctKeys.size() >= budget;
    }

    public int budget() {
        return budget;
    }
}
