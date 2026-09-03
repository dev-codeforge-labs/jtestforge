package com.acme.billing;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Deliberately incomplete: fee() is exercised only on the standard path, and band() only
 * on its lowest branch. The uncovered branches are what a PLAIN_UNIT generate pass should
 * reach - and the measured line-coverage delta is what proves it did.
 */
class PricingRulesTest {

    private final PricingRules subject = new PricingRules();

    @Test
    void chargesTheStandardRate() {
        assertThat(subject.fee(new BigDecimal("100.00"), false)).isEqualByComparingTo("2.10");
    }

    @Test
    void bandsTheSmallestAmount() {
        assertThat(subject.band(5_000)).isEqualTo(1);
    }
}
