package com.devmanchego.jtestforge.spring;

import java.util.List;
import java.util.Objects;

/**
 * What {@link ContextKeyGuard} concluded about one generated candidate.
 *
 * <p>The three outcomes are deliberately distinct because the engine reacts differently to
 * each: an accepted candidate is merged, a key fork is a plain rejection recorded against
 * the unit, and a missing mock bean is an <em>escalation</em> - the class's mock-bean set
 * is recomputed and re-synthesised once, invalidating that class's cached context exactly
 * one time rather than per test (§7.6).
 *
 * @param outcome              what to do with the candidate
 * @param reason               human-readable explanation, recorded on the unit
 * @param missingMockBeanTypes for an escalation, the types the class must start mocking
 */
public record ContextKeyDecision(Outcome outcome, String reason, List<String> missingMockBeanTypes) {

    public enum Outcome {
        ACCEPTED,
        REJECTED_KEY_FORK,
        ESCALATION_REQUIRED
    }

    public ContextKeyDecision {
        Objects.requireNonNull(outcome, "outcome");
        missingMockBeanTypes = missingMockBeanTypes == null ? List.of() : List.copyOf(missingMockBeanTypes);
    }

    public static ContextKeyDecision accepted() {
        return new ContextKeyDecision(Outcome.ACCEPTED, null, List.of());
    }

    public static ContextKeyDecision rejected(String reason) {
        return new ContextKeyDecision(Outcome.REJECTED_KEY_FORK, reason, List.of());
    }

    public static ContextKeyDecision escalation(String reason, List<String> missingMockBeanTypes) {
        return new ContextKeyDecision(Outcome.ESCALATION_REQUIRED, reason, missingMockBeanTypes);
    }

    public boolean isAccepted() {
        return outcome == Outcome.ACCEPTED;
    }
}
