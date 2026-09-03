package com.devmanchego.jtestforge.spring;

import com.devmanchego.jtestforge.model.ProductionClass;
import com.devmanchego.jtestforge.model.ProductionMethod;
import com.devmanchego.jtestforge.model.ProductionParameter;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Renders one handler's HTTP contract as a line of English for
 * {@code {{REQUEST_MAPPINGS}}} — jtestforge-specification.md §6.1.
 *
 * <p>Lives in the {@code spring} package, beside {@link RequestMappingReader} whose
 * parsing it reuses, rather than in {@code prompt}: what counts as a request mapping is
 * Spring knowledge, and the prompt layer should not have to learn it a second time.
 */
public final class RequestMappingDescription {

    private RequestMappingDescription() {
    }

    /** @return the description, or empty if this method is not a request handler. */
    public static Optional<String> of(ProductionClass productionClass, ProductionMethod method) {
        Optional<String> mappingAnnotation = RequestMappingReader.mappingAnnotationOf(method);
        if (mappingAnnotation.isEmpty()) {
            return Optional.empty();
        }
        String httpMethod = RequestMappingReader.httpMethodOf(method, mappingAnnotation.get());
        String path = RequestMappingReader.fullPathOf(productionClass, method, mappingAnnotation.get());

        StringBuilder description = new StringBuilder("- `")
                .append(httpMethod).append(' ').append(path.isEmpty() ? "(no path declared)" : path)
                .append("` -> `").append(method.name()).append("`");

        List<String> boundParameters = describeBoundParameters(method);
        if (!boundParameters.isEmpty()) {
            description.append(", binding ").append(String.join(", ", boundParameters));
        }
        String attributes = method.annotationAttribute(mappingAnnotation.get());
        if (attributes != null && attributes.contains("produces")) {
            description.append(", produces the declared content type");
        }
        return Optional.of(description.toString());
    }

    private static List<String> describeBoundParameters(ProductionMethod method) {
        List<String> described = new ArrayList<>();
        for (ProductionParameter parameter : method.parameters()) {
            for (String annotation : parameter.annotations()) {
                String simpleName = annotation.substring(annotation.lastIndexOf('.') + 1);
                if (SpringAnnotations.REQUEST_BINDING_PARAMETERS.stream()
                        .anyMatch(known -> known.endsWith("." + simpleName))) {
                    described.add("`" + parameter.name() + "` from @" + simpleName);
                    break;
                }
            }
        }
        return described;
    }
}
