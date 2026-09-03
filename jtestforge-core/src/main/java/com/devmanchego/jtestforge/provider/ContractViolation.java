package com.devmanchego.jtestforge.provider;

import java.util.Objects;

/**
 * A fatal defect in an AI response's shape — jtestforge-specification.md §6.2. Drives
 * the single corrective re-prompt (§9.4); if the re-prompt still violates the contract,
 * the unit is failed.
 *
 * @param kind    which rule was broken
 * @param message human-readable detail, suitable for both a log line and the corrective
 *                re-prompt's explanation of what went wrong
 */
public record ContractViolation(ContractViolationKind kind, String message) {

    public ContractViolation {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(message, "message");
    }
}
