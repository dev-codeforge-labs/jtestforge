package com.acme.billing.web;

import com.acme.billing.OrderService;
import com.acme.billing.OrderView;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;

/**
 * The gate 2 fixture. Both handler bodies are reachable by a direct method call and are
 * fully line-covered by OrderControllerTest - yet the mapping paths, the HTTP methods, the
 * request binding and the @Valid constraints are all framework-mediated and therefore
 * completely unverified by that test. A coverage-only tool sees nothing left to do here.
 */
@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @GetMapping("/{reference}")
    public ResponseEntity<OrderView> findByReference(@PathVariable String reference) {
        Optional<OrderView> found = orderService.findByReference(reference);
        if (found.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(found.get());
    }

    @PostMapping
    public ResponseEntity<OrderView> create(@Valid @RequestBody CreateOrderRequest request) {
        OrderView created = orderService.create(request.reference(), request.amountInCents());
        return ResponseEntity.status(201).body(created);
    }
}
