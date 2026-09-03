package com.acme.billing.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

public record CreateOrderRequest(
        @NotBlank(message = "reference must not be blank") String reference,
        @Positive(message = "amountInCents must be positive") long amountInCents) {
}
