package com.devmanchego.jtestforge.orchestration;

import com.devmanchego.jtestforge.model.Tier;
import com.devmanchego.jtestforge.model.WorkUnit;
import com.devmanchego.jtestforge.model.WorkUnitId;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** §9.6's two ordering rules, tested as pure sorting logic. */
class TierSchedulerTest {

    @Test
    void everyPlainUnitRunsBeforeAnySpringTierUnit() {
        List<WorkUnit> input = List.of(
                unit("com.acme.OrderController", "findById()", Tier.WEB_SLICE, "OrderControllerWebTest.java"),
                unit("com.acme.PaymentService", "applyFee()", Tier.PLAIN_UNIT, "PaymentServiceTest.java"),
                unit("com.acme.OrderRepository", "findByRef()", Tier.DATA_SLICE, "OrderRepositoryDataTest.java"),
                unit("com.acme.PaymentService", "settle()", Tier.PLAIN_UNIT, "PaymentServiceTest.java"));

        List<WorkUnit> ordered = TierScheduler.order(input);

        assertThat(ordered).extracting(unit -> unit.tier())
                .containsExactly(Tier.PLAIN_UNIT, Tier.PLAIN_UNIT, Tier.WEB_SLICE, Tier.DATA_SLICE);
    }

    @Test
    void unitsForTheSameTestClassRunConsecutivelyWithinATier() {
        // Five units on one controller must not be interleaved with another controller's
        // units, or the run bounces between classes for no benefit (§9.6).
        List<WorkUnit> input = List.of(
                unit("com.acme.web.OrderController", "findById()", Tier.WEB_SLICE, "OrderControllerWebTest.java"),
                unit("com.acme.web.CustomerController", "findById()", Tier.WEB_SLICE, "CustomerControllerWebTest.java"),
                unit("com.acme.web.OrderController", "create()", Tier.WEB_SLICE, "OrderControllerWebTest.java"),
                unit("com.acme.web.OrderController", "delete()", Tier.WEB_SLICE, "OrderControllerWebTest.java"));

        List<WorkUnit> ordered = TierScheduler.order(input);

        assertThat(ordered).extracting(WorkUnit::testFile).containsExactly(
                "OrderControllerWebTest.java", "OrderControllerWebTest.java",
                "OrderControllerWebTest.java", "CustomerControllerWebTest.java");
    }

    @Test
    void aFixtureWithNoTierOrClassVariationKeepsDiscoveryOrderExactly() {
        List<WorkUnit> input = List.of(
                unit("com.acme.PaymentService", "a()", Tier.PLAIN_UNIT, "PaymentServiceTest.java"),
                unit("com.acme.PaymentService", "b()", Tier.PLAIN_UNIT, "PaymentServiceTest.java"),
                unit("com.acme.PaymentService", "c()", Tier.PLAIN_UNIT, "PaymentServiceTest.java"));

        assertThat(TierScheduler.order(input)).containsExactlyElementsOf(input);
    }

    @Test
    void anEmptyListOrdersToAnEmptyList() {
        assertThat(TierScheduler.order(List.of())).isEmpty();
    }

    @Test
    void aClassRevisitedLaterInTheSameTierStillGroupsWithItsFirstAppearance() {
        // If discovery interleaves a class's units with another's, grouping by first
        // appearance still keeps every one of that class's units together, rather than
        // scattering the later ones after everything else.
        List<WorkUnit> input = List.of(
                unit("com.acme.web.OrderController", "findById()", Tier.WEB_SLICE, "OrderControllerWebTest.java"),
                unit("com.acme.web.CustomerController", "findById()", Tier.WEB_SLICE, "CustomerControllerWebTest.java"),
                unit("com.acme.web.OrderController", "create()", Tier.WEB_SLICE, "OrderControllerWebTest.java"));

        List<WorkUnit> ordered = TierScheduler.order(input);

        assertThat(ordered).extracting(WorkUnit::testFile).containsExactly(
                "OrderControllerWebTest.java", "OrderControllerWebTest.java", "CustomerControllerWebTest.java");
    }

    private WorkUnit unit(String className, String methodSignature, Tier tier, String testFile) {
        return WorkUnit.pending(WorkUnitId.of(className, methodSignature, tier),
                testFile, className.replace('.', '/') + ".java", "sha256:src");
    }
}
