package com.devmanchego.jtestforge.spring;

import com.devmanchego.jtestforge.model.ContextKey;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Wraps {@link ContextLoadCounter} with the bookkeeping needed to name offenders —
 * jtestforge-specification.md §7.6, §9.6, §14.1 exit code 8.
 *
 * <p>The counter alone answers "how many distinct contexts has this run loaded"; it
 * cannot answer "which classes caused that", because a {@code Set<ContextKey>} has no
 * memory of which key belonged to which class. That second question is what the exit-8
 * report has to print - "nearly always a context-key violation (§7.6) rather than genuine
 * need" - so this class additionally remembers the first key seen for each test class and
 * flags any later, different key recorded for that same class as a fork.
 */
public final class ContextKeyStabilityTracker {

    private final ContextLoadCounter counter;
    private final Map<String, ContextKey> firstKeySeenByClass = new LinkedHashMap<>();
    private final Set<String> forkedClasses = new LinkedHashSet<>();

    public ContextKeyStabilityTracker(int budget) {
        this.counter = new ContextLoadCounter(budget);
    }

    /**
     * Records that {@code testClassFqn} is about to run (or re-run) with {@code key}.
     *
     * <p>A repeat of the same key for the same class - the overwhelmingly common case,
     * since {@link com.devmanchego.jtestforge.guard.StaticQualityGuards} and
     * {@link ContextKeyGuard} both refuse candidates that would change it - costs nothing
     * further here: {@link ContextLoadCounter} already collapses it. A <em>different</em>
     * key for a class already seen is the fork this class exists to name.
     */
    public void record(String testClassFqn, ContextKey key) {
        counter.record(key);
        ContextKey firstSeen = firstKeySeenByClass.putIfAbsent(testClassFqn, key);
        if (firstSeen != null && !firstSeen.equals(key)) {
            forkedClasses.add(testClassFqn);
        }
    }

    public boolean budgetExhausted() {
        return counter.budgetExhausted();
    }

    public int contextLoads() {
        return counter.contextLoads();
    }

    public int budget() {
        return counter.budget();
    }

    /** Test classes whose recorded context key changed partway through the run. */
    public Set<String> forkedClasses() {
        return Set.copyOf(forkedClasses);
    }
}
