package com.devmanchego.jtestforge.spring;

import com.devmanchego.jtestforge.model.AnnotationNames;
import com.devmanchego.jtestforge.model.ProductionClass;
import com.devmanchego.jtestforge.model.ProductionMethod;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts the HTTP method and path from a Spring request-mapping annotation.
 *
 * <p>Reads the raw attribute text recorded by the scanner rather than a parsed attribute
 * model: the only things needed here are the first string literal and, for
 * {@code @RequestMapping}, the declared {@code method}. Anything richer would be more
 * machinery than either caller (this scanner and §6.1's prompt placeholders) justifies.
 */
final class RequestMappingReader {

    private static final Pattern FIRST_STRING_LITERAL = Pattern.compile("\"([^\"]*)\"");
    private static final Pattern VALUE_OR_PATH_ATTRIBUTE =
            Pattern.compile("(?:value|path)\\s*=\\s*\\{?\\s*\"([^\"]*)\"");
    private static final Pattern REQUEST_METHOD =
            Pattern.compile("method\\s*=\\s*\\{?\\s*(?:RequestMethod\\.)?([A-Z]+)");

    private RequestMappingReader() {
    }

    /** @return the mapping annotation's simple name, if the method has one. */
    static Optional<String> mappingAnnotationOf(ProductionMethod method) {
        return SpringAnnotations.REQUEST_MAPPINGS.stream()
                .map(AnnotationNames::simpleNameOf)
                .filter(method::hasAnnotation)
                .findFirst();
    }

    /**
     * @return the HTTP method the mapping declares. Derived from the annotation's own
     * name for the shorthand forms; read from the {@code method} attribute for
     * {@code @RequestMapping}, which defaults to matching every method when it declares
     * none - reported here as {@code ANY} rather than guessed.
     */
    static String httpMethodOf(ProductionMethod method, String mappingAnnotationSimpleName) {
        return switch (mappingAnnotationSimpleName) {
            case "GetMapping" -> "GET";
            case "PostMapping" -> "POST";
            case "PutMapping" -> "PUT";
            case "DeleteMapping" -> "DELETE";
            case "PatchMapping" -> "PATCH";
            default -> requestMappingMethod(method);
        };
    }

    /**
     * The full path a request must actually use: the controller's class-level
     * {@code @RequestMapping} prefix combined with the method's own path.
     *
     * <p>Reading only the method annotation is wrong for the majority of real
     * controllers, which put the shared prefix on the class. The consequences are not
     * cosmetic: the model would be told to request a path the application does not serve,
     * gap suppression could not recognise an existing test that requests the real one, and
     * the post-merge "did this actually close the gap" check (§9.4 step 9) could never
     * match. Discovered end to end against a real Spring Boot module in phase 18.
     */
    static String fullPathOf(ProductionClass productionClass, ProductionMethod method,
                             String mappingAnnotationSimpleName) {
        return joinPaths(classLevelPathOf(productionClass), pathOf(method, mappingAnnotationSimpleName));
    }

    /** The class-level {@code @RequestMapping} prefix, or {@code ""} when there is none. */
    static String classLevelPathOf(ProductionClass productionClass) {
        String attributes = productionClass.annotationAttribute("RequestMapping");
        if (attributes == null || attributes.isBlank()) {
            return "";
        }
        Matcher named = VALUE_OR_PATH_ATTRIBUTE.matcher(attributes);
        if (named.find()) {
            return named.group(1);
        }
        Matcher literal = FIRST_STRING_LITERAL.matcher(attributes);
        return literal.find() ? literal.group(1) : "";
    }

    /** Joins two path segments the way Spring does, tolerating a missing or extra slash. */
    static String joinPaths(String prefix, String suffix) {
        String left = prefix == null ? "" : prefix.trim();
        String right = suffix == null ? "" : suffix.trim();
        if (left.isEmpty()) {
            return right;
        }
        if (right.isEmpty()) {
            return left;
        }
        String trimmedLeft = left.endsWith("/") ? left.substring(0, left.length() - 1) : left;
        String trimmedRight = right.startsWith("/") ? right : "/" + right;
        return trimmedLeft + trimmedRight;
    }

    /** @return the method-level mapped path, or {@code ""} when the annotation declares none. */
    static String pathOf(ProductionMethod method, String mappingAnnotationSimpleName) {
        String attributes = method.annotationAttribute(mappingAnnotationSimpleName);
        if (attributes == null || attributes.isBlank()) {
            return "";
        }
        Matcher named = VALUE_OR_PATH_ATTRIBUTE.matcher(attributes);
        if (named.find()) {
            return named.group(1);
        }
        // Single-member form: @GetMapping("/api/orders")
        Matcher literal = FIRST_STRING_LITERAL.matcher(attributes);
        return literal.find() ? literal.group(1) : "";
    }

    private static String requestMappingMethod(ProductionMethod method) {
        String attributes = method.annotationAttribute("RequestMapping");
        if (attributes == null) {
            return "ANY";
        }
        Matcher matcher = REQUEST_METHOD.matcher(attributes);
        return matcher.find() ? matcher.group(1).toUpperCase(Locale.ROOT) : "ANY";
    }
}
