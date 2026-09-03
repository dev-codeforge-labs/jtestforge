package com.acme;

import java.math.BigDecimal;

/** A plain collaborator interface, never itself a scan candidate (not a concrete class). */
public interface PaymentGateway {
    boolean charge(BigDecimal amount);
}
