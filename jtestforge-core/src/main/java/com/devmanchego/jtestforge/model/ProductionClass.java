package com.devmanchego.jtestforge.model;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * One production type found by a source scan — jtestforge-specification.md §7.1.
 *
 * <p>Normally a concrete, testable class: {@code ProductionClassScanner} filters out
 * interfaces, abstract classes, enums and records, since none of those can receive a
 * Mockito unit test. The one deliberate exception is a Spring Data repository interface,
 * emitted by {@code SpringDataRepositoryScanner} because its derived queries have no
 * bodies to unit-test at all and must go straight to a {@code @DataJpaTest} (§7.4).
 *
 * @param fqn           fully-qualified class name
 * @param sourceFile    absolute path to the {@code .java} file this was parsed from
 * @param annotations   best-effort resolved FQNs of the class's own annotations (§7.1);
 *                      falls back to the as-written name when resolution via imports fails
 * @param supertypes    simple names of the types this one extends or implements. Recorded
 *                      as a plain structural fact so that framework-specific
 *                      interpretation - "extends JpaRepository, therefore a Spring Data
 *                      repository" - stays in the {@code spring} package rather than
 *                      leaking a framework concern into the general scanner
 * @param collaborators dependencies this class needs mocked (§7.1)
 * @param methods       the class's own declared methods
 * @param annotationAttributes attribute text of the class's own annotations, keyed by
 *                      simple name. Carries what a bare annotation name cannot - notably
 *                      the class-level {@code @RequestMapping("/api/orders")} prefix that
 *                      every method path in the class is relative to
 */
public record ProductionClass(
        String fqn,
        Path sourceFile,
        List<String> annotations,
        List<String> supertypes,
        List<Collaborator> collaborators,
        List<ProductionMethod> methods,
        java.util.Map<String, String> annotationAttributes) {

    public ProductionClass {
        Objects.requireNonNull(fqn, "fqn");
        Objects.requireNonNull(sourceFile, "sourceFile");
        annotations = annotations == null ? List.of() : List.copyOf(annotations);
        supertypes = supertypes == null ? List.of() : List.copyOf(supertypes);
        collaborators = collaborators == null ? List.of() : List.copyOf(collaborators);
        methods = methods == null ? List.of() : List.copyOf(methods);
        annotationAttributes = annotationAttributes == null
                ? java.util.Map.of() : java.util.Map.copyOf(annotationAttributes);
    }

    /** A class with no recorded annotation attributes - the shape every non-web caller needs. */
    public ProductionClass(String fqn, Path sourceFile, List<String> annotations, List<String> supertypes,
                           List<Collaborator> collaborators, List<ProductionMethod> methods) {
        this(fqn, sourceFile, annotations, supertypes, collaborators, methods, java.util.Map.of());
    }

    /** Raw attribute text of one of this class's annotations, keyed by its simple name. */
    public String annotationAttribute(String annotationSimpleName) {
        return annotationAttributes.get(annotationSimpleName);
    }

    /** The package part of {@link #fqn()}, or {@code ""} for the default package. */
    public String packageName() {
        int lastDot = fqn.lastIndexOf('.');
        return lastDot < 0 ? "" : fqn.substring(0, lastDot);
    }

    public String simpleName() {
        int lastDot = fqn.lastIndexOf('.');
        return lastDot < 0 ? fqn : fqn.substring(lastDot + 1);
    }

    /**
     * Whether this class carries the given annotation, matched by FQN or by simple name -
     * see {@link AnnotationNames} for why both forms must be accepted.
     */
    public boolean hasAnnotation(String annotationName) {
        return AnnotationNames.contains(annotations, annotationName);
    }

    public boolean hasAnyAnnotation(List<String> annotationNames) {
        return AnnotationNames.containsAny(annotations, annotationNames);
    }

    public boolean extendsAnyOf(java.util.Collection<String> supertypeSimpleNames) {
        return supertypes.stream().anyMatch(supertypeSimpleNames::contains);
    }
}
