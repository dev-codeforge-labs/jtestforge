package com.acme;

import org.slf4j.Logger;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;

/**
 * Fixture: constructor injection of a module-local collaborator (PaymentGateway,
 * resolved from source) and a collaborator only available via the compile classpath
 * (org.slf4j.Logger, resolved by reflection over the running JVM) - exercises both
 * resolution paths a real target module's classpath would need.
 */
public class PaymentService {

    private final PaymentGateway gateway;
    private final Logger logger;

    public PaymentService(PaymentGateway gateway, Logger logger) {
        this.gateway = gateway;
        this.logger = logger;
    }

    public BigDecimal applyFee(BigDecimal amount, Currency currency) {
        if (amount.signum() < 0) {
            throw new IllegalArgumentException("amount must not be negative");
        }
        BigDecimal fee;
        if (amount.compareTo(BigDecimal.valueOf(100)) > 0) {
            fee = amount.multiply(BigDecimal.valueOf(0.01));
        } else {
            fee = BigDecimal.valueOf(1);
        }
        return amount.add(fee).setScale(currency.getDefaultFractionDigits(), RoundingMode.HALF_UP);
    }

    public PaymentGateway gateway() {
        return gateway;
    }
}
