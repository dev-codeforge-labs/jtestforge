package com.devmanchego.jtestforge.model;

import java.util.List;
import java.util.Objects;

/**
 * One parameter of a production method, with the annotations written on it —
 * jtestforge-specification.md §7.1, §7.5.
 *
 * <p>Parameter annotations are recorded rather than discarded because they carry
 * framework contract that the parameter's type alone does not: {@code @PathVariable},
 * {@code @RequestParam}, {@code @RequestBody} and {@code @Valid} are precisely the
 * signals §7.5 scans for, and they feed {@code {{REQUEST_MAPPINGS}}} and
 * {@code {{VALIDATION_CONSTRAINTS}}} in §6.1.
 *
 * @param name        parameter name as declared
 * @param typeFqn     resolved type FQN, or the as-written type on resolution failure
 * @param annotations best-effort resolved FQNs of the parameter's annotations
 */
public record ProductionParameter(String name, String typeFqn, List<String> annotations) {

    public ProductionParameter {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(typeFqn, "typeFqn");
        annotations = annotations == null ? List.of() : List.copyOf(annotations);
    }

    /** Whether this parameter carries the given annotation, by FQN or by simple name. */
    public boolean hasAnnotation(String annotationName) {
        return AnnotationNames.contains(annotations, annotationName);
    }
}
