package com.acme.billing;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Plain domain logic - no Spring, no collaborators. Deliberately branchy, and
 * deliberately only partly covered by the module's existing tests: the uncovered branches
 * are what a PLAIN_UNIT generate pass is expected to reach.
 */
public class PricingRules {

    private static final BigDecimal STANDARD_RATE = new BigDecimal("0.021");
    private static final BigDecimal PREMIUM_RATE = new BigDecimal("0.014");

    public BigDecimal fee(BigDecimal amount, boolean premium) {
        if (amount == null || amount.signum() < 0) {
            throw new IllegalArgumentException("amount must not be negative");
        }
        BigDecimal rate = premium ? PREMIUM_RATE : STANDARD_RATE;
        return amount.multiply(rate).setScale(2, RoundingMode.HALF_UP);
    }

    public int band(int amountInCents) {
        if (amountInCents < 0) {
            throw new IllegalArgumentException("amount must not be negative");
        }
        if (amountInCents <= 10_000) {
            return 1;
        }
        if (amountInCents <= 100_000) {
            return 2;
        }
        return 3;
    }
}
