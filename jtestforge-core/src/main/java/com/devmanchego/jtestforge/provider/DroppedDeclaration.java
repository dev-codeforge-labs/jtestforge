package com.devmanchego.jtestforge.provider;

import java.util.Objects;

/**
 * One declaration the response parser discarded from an otherwise-usable response —
 * jtestforge-specification.md §6.2 rule 5. Recorded, never silently swallowed: the run
 * report's "prompt effectiveness" table (§15) is built from exactly these.
 *
 * @param description what the declaration was (e.g. a method name, or "a field named x")
 * @param reason      why it was dropped
 */
public record DroppedDeclaration(String description, String reason) {

    public DroppedDeclaration {
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(reason, "reason");
    }
}
