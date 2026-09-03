package com.acme.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Fixture: the case that justifies the framework-semantic gate existing at all. Every
 * line of every handler body here is trivially reachable from a plain unit test that
 * calls the methods directly - so this class can sit at 100% line coverage while its
 * mapping, binding, validation, security and error translation are entirely unverified.
 */
@RestController
public class OrderController {

    @GetMapping(value = "/api/orders/{id}", produces = "application/json")
    public ResponseEntity<String> findById(@PathVariable Long id, @RequestParam(required = false) String expand) {
        return ResponseEntity.ok("order-" + id);
    }

    @PostMapping("/api/orders")
    public ResponseEntity<String> create(@Valid @RequestBody CreateOrderRequest request) {
        return ResponseEntity.ok(request.reference());
    }

    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/api/orders/{id}/audit")
    public ResponseEntity<String> audit(@PathVariable Long id) {
        return ResponseEntity.ok("audit-" + id);
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<String> handleIllegalState(IllegalStateException exception) {
        return ResponseEntity.status(409).body(exception.getMessage());
    }

    /** Request payload carrying the Bean Validation constraints. */
    public record CreateOrderRequest(@NotBlank String reference) {
    }
}
