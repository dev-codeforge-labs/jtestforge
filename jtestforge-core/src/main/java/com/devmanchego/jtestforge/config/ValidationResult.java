package com.devmanchego.jtestforge.config;

import java.util.List;

/**
 * Outcome of {@link ConfigValidator#validate}. All violations found are collected
 * before returning - jtestforge-specification.md §5.1: "every violation is reported at
 * once rather than one per run." {@code errors} block the run; {@code warnings} do not.
 */
public record ValidationResult(List<ConfigViolation> errors, List<ConfigViolation> warnings) {

    public ValidationResult {
        errors = List.copyOf(errors);
        warnings = List.copyOf(warnings);
    }

    public boolean isValid() {
        return errors.isEmpty();
    }
}
