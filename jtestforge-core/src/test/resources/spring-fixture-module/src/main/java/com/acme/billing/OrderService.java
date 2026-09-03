package com.acme.billing;

import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

@Service
public class OrderService {

    private final Map<String, OrderView> orders = new ConcurrentHashMap<>();

    public Optional<OrderView> findByReference(String reference) {
        if (reference == null || reference.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(orders.get(reference));
    }

    public OrderView create(String reference, long amountInCents) {
        OrderView view = new OrderView(reference, amountInCents);
        orders.put(reference, view);
        return view;
    }
}
