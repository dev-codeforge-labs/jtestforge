package com.acme;

import jakarta.persistence.Entity;

/**
 * Fixture: a JPA entity, which must be excluded via {@code selection.excludeAnnotations}.
 * jakarta.persistence is never an actual dependency of jtestforge itself - the import
 * statement alone is enough for annotation exclusion to work correctly (see
 * AnnotationFqnResolver), which is the point being exercised here.
 */
@Entity
public class CustomerEntity {

    private Long id;
    private String name;

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }
}
