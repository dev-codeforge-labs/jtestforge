package com.acme;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Fixture: a plain {@code @Service}. Its branching is ordinary Java, reachable from a
 * direct call, so it must stay at PLAIN_UNIT and raise no Spring gap for that.
 *
 * <p>{@code settle} additionally declares a rollback rule, which IS framework-mediated:
 * whether the transaction actually rolls back for that exception type cannot be observed
 * by calling the method directly. {@code archive} is {@code @Transactional} with no
 * rollback rule, and must raise no rollback gap - there is no declared behaviour to
 * verify beyond Spring's own default.
 */
@Service
public class BillingService {

    @Transactional(rollbackFor = IllegalStateException.class)
    public void settle(String invoiceId) {
        if (invoiceId == null) {
            throw new IllegalStateException("invoiceId is required");
        }
        post(invoiceId);
    }

    @Transactional
    public void archive(String invoiceId) {
        post(invoiceId);
    }

    public int classify(int amount) {
        if (amount > 1000) {
            return 2;
        }
        return amount > 100 ? 1 : 0;
    }

    private void post(String invoiceId) {
        // no-op in the fixture
    }
}
