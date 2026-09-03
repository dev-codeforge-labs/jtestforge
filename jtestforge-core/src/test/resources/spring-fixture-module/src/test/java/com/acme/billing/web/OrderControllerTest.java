package com.acme.billing.web;

import com.acme.billing.OrderService;
import com.acme.billing.OrderView;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The gate 2 baseline: a plain, direct-call unit test that reaches EVERY line and branch
 * of both handlers, and proves nothing whatsoever about the paths, HTTP methods, request
 * binding or validation constraints - all of which live in annotations the framework
 * interprets, not in the bodies this test calls.
 */
class OrderControllerTest {

    private final OrderService orderService = new OrderService();
    private final OrderController subject = new OrderController(orderService);

    @Test
    void findByReference_returnsNotFoundWhenAbsent() {
        ResponseEntity<OrderView> response = subject.findByReference("MISSING");

        assertThat(response.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void findByReference_returnsTheOrderWhenPresent() {
        orderService.create("REF-1", 4_200L);

        ResponseEntity<OrderView> response = subject.findByReference("REF-1");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isEqualTo(new OrderView("REF-1", 4_200L));
    }

    @Test
    void create_storesAndReturnsTheOrder() {
        ResponseEntity<OrderView> response = subject.create(new CreateOrderRequest("REF-2", 999L));

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        assertThat(response.getBody()).isEqualTo(new OrderView("REF-2", 999L));
    }
}
