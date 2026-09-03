package com.acme.config;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Fixture: a {@code @ConfigurationProperties} type with a constraint. Binding, relaxed
 * naming, defaults and validation failure are all framework behaviour - none of it
 * observable by constructing this object directly (§7.5).
 */
@ConfigurationProperties(prefix = "acme.billing")
public class BillingProperties {

    @Min(1)
    private int retryAttempts = 3;

    private String currency = "EUR";

    public int getRetryAttempts() {
        return retryAttempts;
    }

    public void setRetryAttempts(int retryAttempts) {
        this.retryAttempts = retryAttempts;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }
}
