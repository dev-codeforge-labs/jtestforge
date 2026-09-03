package com.devmanchego.jtestforge.orchestration;

import com.devmanchego.jtestforge.analysis.MockBeanSynthesizer;
import com.devmanchego.jtestforge.spring.ContextKeyModel;
import com.devmanchego.jtestforge.spring.ContextKeyStabilityTracker;
import com.devmanchego.jtestforge.spring.ContextKeyGuard;
import com.devmanchego.jtestforge.spring.MockBeanEscalation;
import com.devmanchego.jtestforge.spring.MockBeanSetResolver;

import java.util.Objects;

/**
 * The Spring-tier-specific collaborators {@link DefaultUnitProcessor} needs on top of the
 * plain per-unit loop — jtestforge-specification.md §7.6.
 *
 * <p>Grouped into one value rather than six more constructor parameters because they share
 * a lifetime: {@link MockBeanEscalation} and {@link ContextKeyStabilityTracker} carry
 * state across every unit of one run (an escalation is allowed once per class <em>for the
 * whole run</em>; the context-load budget is a run-wide ceiling), so exactly one instance
 * of each must be constructed per run and shared between every unit the processor handles
 * - and, for the tracker, with {@link GenerateEngine} itself, which is what checks
 * {@link ContextKeyStabilityTracker#budgetExhausted()} after each unit.
 */
public record SpringGenerationSupport(
        ContextKeyGuard contextKeyGuard,
        MockBeanEscalation mockBeanEscalation,
        MockBeanSynthesizer mockBeanSynthesizer,
        MockBeanSetResolver mockBeanSetResolver,
        ContextKeyModel contextKeyModel,
        ContextKeyStabilityTracker contextKeyStabilityTracker) {

    public SpringGenerationSupport {
        Objects.requireNonNull(contextKeyGuard, "contextKeyGuard");
        Objects.requireNonNull(mockBeanEscalation, "mockBeanEscalation");
        Objects.requireNonNull(mockBeanSynthesizer, "mockBeanSynthesizer");
        Objects.requireNonNull(mockBeanSetResolver, "mockBeanSetResolver");
        Objects.requireNonNull(contextKeyModel, "contextKeyModel");
        Objects.requireNonNull(contextKeyStabilityTracker, "contextKeyStabilityTracker");
    }

    /** Fresh, unshared support for one run, with the given context-load budget. */
    public static SpringGenerationSupport forNewRun(int contextLoadBudget) {
        return new SpringGenerationSupport(
                new ContextKeyGuard(), new MockBeanEscalation(), new MockBeanSynthesizer(),
                new MockBeanSetResolver(), new ContextKeyModel(),
                new ContextKeyStabilityTracker(contextLoadBudget));
    }
}
