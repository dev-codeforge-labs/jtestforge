package com.devmanchego.jtestforge.model;

import java.util.Objects;

/**
 * One mock bean a generated Spring slice class declares.
 *
 * <p>The <em>set</em> of these is part of Spring's context cache key (§7.6), which is why
 * it is derived once per class from the production class's collaborators rather than
 * accumulated test by test. Adding one later is a context-key change, not a local edit.
 *
 * @param name     field name to declare
 * @param typeName type to mock, as it should appear in source
 */
public record MockBeanDeclaration(String name, String typeName) {

    public MockBeanDeclaration {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(typeName, "typeName");
    }

    /** The simple type name, which is what a generated field declaration uses. */
    public String simpleTypeName() {
        int lastDot = typeName.lastIndexOf('.');
        return lastDot < 0 ? typeName : typeName.substring(lastDot + 1);
    }
}
