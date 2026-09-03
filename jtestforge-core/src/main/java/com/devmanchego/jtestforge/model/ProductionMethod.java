package com.devmanchego.jtestforge.model;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One method of a scanned production class — jtestforge-specification.md §7.1.
 *
 * @param name                  method name
 * @param returnType            resolved return type FQN, or {@code "void"}. Load-bearing
 *                              for two quality guards (§11.1): a verify-only test is
 *                              legitimate on a void method (there is no return value to
 *                              assert) and worthless on one that returns something, and a
 *                              bare-status web assertion is only acceptable where the
 *                              handler genuinely has no body to check
 * @param parameters            parameters in declaration order, with their annotations
 * @param visibility            access modifier
 * @param isStatic              whether the method is static
 * @param annotations           best-effort resolved FQNs of the method's own annotations
 * @param annotationAttributes  attribute text of the method's annotations, keyed by the
 *                              annotation's simple name; carries the mapping path,
 *                              {@code produces}, rollback rules and so on that §7.5 reads
 * @param thrownTypes           declared checked exceptions, as written in source
 * @param startLine             first line of the method declaration (1-based)
 * @param endLine               last line of the method declaration (1-based)
 * @param cyclomaticComplexity  approximate complexity (branch count + 1), per §5
 *                              {@code selection.minComplexity}
 */
public record ProductionMethod(
        String name,
        String returnType,
        List<ProductionParameter> parameters,
        Visibility visibility,
        boolean isStatic,
        List<String> annotations,
        Map<String, String> annotationAttributes,
        List<String> thrownTypes,
        int startLine,
        int endLine,
        int cyclomaticComplexity) {

    public ProductionMethod {
        Objects.requireNonNull(name, "name");
        returnType = returnType == null ? "void" : returnType;
        Objects.requireNonNull(visibility, "visibility");
        parameters = parameters == null ? List.of() : List.copyOf(parameters);
        annotations = annotations == null ? List.of() : List.copyOf(annotations);
        annotationAttributes = annotationAttributes == null ? Map.of() : Map.copyOf(annotationAttributes);
        thrownTypes = thrownTypes == null ? List.of() : List.copyOf(thrownTypes);
    }

    /** Resolved parameter types, in declaration order. */
    public List<String> parameterTypes() {
        return parameters.stream().map(ProductionParameter::typeFqn).toList();
    }

    /** Whether this method returns nothing - see {@code returnType}'s note on the guards. */
    public boolean isVoid() {
        return "void".equals(returnType);
    }

    /** {@code name(Type1,Type2,...)}, the form used throughout {@link WorkUnitId}. */
    public String signature() {
        return name + "(" + String.join(",", parameterTypes()) + ")";
    }

    /** Whether this method carries the given annotation, by FQN or by simple name. */
    public boolean hasAnnotation(String annotationName) {
        return AnnotationNames.contains(annotations, annotationName);
    }

    public boolean hasAnyAnnotation(List<String> annotationNames) {
        return AnnotationNames.containsAny(annotations, annotationNames);
    }

    /** Raw attribute text of an annotation, keyed by its simple name. */
    public String annotationAttribute(String annotationSimpleName) {
        return annotationAttributes.get(annotationSimpleName);
    }
}
