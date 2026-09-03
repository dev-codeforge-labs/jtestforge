package com.devmanchego.jtestforge.spring;

import com.devmanchego.jtestforge.model.ContextKey;
import com.devmanchego.jtestforge.model.Tier;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** §7.6, §9.6, and the exit-8 report's "classes that forked a context" (§14.1). */
class ContextKeyStabilityTrackerTest {

    @Test
    void fiveUnitsOnOneControllerWithTheSameKeyCostExactlyOneContextLoad() {
        ContextKeyStabilityTracker tracker = new ContextKeyStabilityTracker(40);
        ContextKey key = webSliceKey(List.of("OrderController"), Set.of());

        for (int i = 0; i < 5; i++) {
            tracker.record("com.acme.web.OrderControllerWebTest", key);
        }

        assertThat(tracker.contextLoads()).isEqualTo(1);
        assertThat(tracker.forkedClasses()).isEmpty();
    }

    @Test
    void differentClassesEachCostTheirOwnLoad() {
        ContextKeyStabilityTracker tracker = new ContextKeyStabilityTracker(40);

        tracker.record("com.acme.web.OrderControllerWebTest", webSliceKey(List.of("OrderController"), Set.of()));
        tracker.record("com.acme.web.CustomerControllerWebTest", webSliceKey(List.of("CustomerController"), Set.of()));

        assertThat(tracker.contextLoads()).isEqualTo(2);
        assertThat(tracker.forkedClasses()).isEmpty();
    }

    @Test
    void aDifferentKeyRecordedLaterForTheSameClassIsNamedAsAFork() {
        // The overwhelmingly common cause: a candidate slipped past the guards and
        // changed the class's mock-bean set or annotations mid-run.
        ContextKeyStabilityTracker tracker = new ContextKeyStabilityTracker(40);
        ContextKey original = webSliceKey(List.of("OrderController"), Set.of("PaymentGateway"));
        ContextKey forked = webSliceKey(List.of("OrderController"), Set.of("PaymentGateway", "AuditLog"));

        tracker.record("com.acme.web.OrderControllerWebTest", original);
        tracker.record("com.acme.web.OrderControllerWebTest", forked);

        assertThat(tracker.forkedClasses()).containsExactly("com.acme.web.OrderControllerWebTest");
        assertThat(tracker.contextLoads()).isEqualTo(2);
    }

    @Test
    void exceedingTheBudgetAbortsAndTheOffendingClassIsNamed() {
        ContextKeyStabilityTracker tracker = new ContextKeyStabilityTracker(2);
        ContextKey original = webSliceKey(List.of("OrderController"), Set.of());
        ContextKey forked = webSliceKey(List.of("OrderController"), Set.of("AuditLog"));

        tracker.record("com.acme.web.OrderControllerWebTest", original);
        assertThat(tracker.budgetExhausted()).isFalse();

        tracker.record("com.acme.web.OrderControllerWebTest", forked);

        assertThat(tracker.budgetExhausted()).isTrue();
        assertThat(tracker.forkedClasses()).containsExactly("com.acme.web.OrderControllerWebTest");
    }

    @Test
    void budgetExhaustedByGenuinelyDistinctClassesNamesNoOffender() {
        // Exhaustion is not always a violation: enough genuinely different controllers
        // can exhaust the budget on their own, and there is nothing to name.
        ContextKeyStabilityTracker tracker = new ContextKeyStabilityTracker(1);

        tracker.record("com.acme.web.OrderControllerWebTest", webSliceKey(List.of("OrderController"), Set.of()));
        tracker.record("com.acme.web.CustomerControllerWebTest", webSliceKey(List.of("CustomerController"), Set.of()));

        assertThat(tracker.budgetExhausted()).isTrue();
        assertThat(tracker.forkedClasses()).isEmpty();
    }

    private ContextKey webSliceKey(List<String> configurationClasses, Set<String> mockBeanTypes) {
        return new ContextKey(Tier.WEB_SLICE, configurationClasses, List.of(), List.of(),
                mockBeanTypes, "", List.of());
    }
}
