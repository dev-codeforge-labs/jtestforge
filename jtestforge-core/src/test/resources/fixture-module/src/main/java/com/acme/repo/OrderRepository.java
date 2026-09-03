package com.acme.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

/**
 * Fixture: a Spring Data repository interface. Its derived query has no body at all, so a
 * plain unit test is not merely inefficient here - there is nothing to unit-test
 * (§7.4). This must classify directly as DATA_SLICE, and be reported unavailable with a
 * named reason when no embedded database is present.
 */
public interface OrderRepository extends JpaRepository<Object, Long> {

    List<Object> findByReferenceAndStatus(String reference, String status);

    @Query("select o from Order o where o.total > ?1")
    List<Object> findExpensive(long threshold);
}
