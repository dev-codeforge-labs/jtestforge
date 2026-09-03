package com.devmanchego.jtestforge.model;

import java.util.Objects;

/**
 * One mock or spy field declared on a test class.
 *
 * <p>For a Spring slice these are part of the context cache key (§7.6), which is why the
 * set is recorded rather than merely counted: adding one is a context-key change, not a
 * local edit.
 *
 * @param name           field name
 * @param typeName       declared type, as written
 * @param annotationName the simple name of the annotation that made it a mock
 */
public record MockField(String name, String typeName, String annotationName) {

    public MockField {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(typeName, "typeName");
        Objects.requireNonNull(annotationName, "annotationName");
    }

    /** Whether this is a Spring mock bean, and therefore part of the context cache key. */
    public boolean isSpringMockBean() {
        return annotationName.equals("MockBean") || annotationName.equals("MockitoBean")
                || annotationName.equals("SpyBean") || annotationName.equals("MockitoSpyBean");
    }
}
