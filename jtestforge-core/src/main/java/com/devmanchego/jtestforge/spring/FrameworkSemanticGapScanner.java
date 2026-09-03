package com.devmanchego.jtestforge.spring;

import com.devmanchego.jtestforge.model.AnnotationNames;
import com.devmanchego.jtestforge.model.ProductionClass;
import com.devmanchego.jtestforge.model.ProductionMethod;
import com.devmanchego.jtestforge.model.ProductionParameter;
import com.devmanchego.jtestforge.model.SemanticGap;
import com.devmanchego.jtestforge.model.SemanticGapKind;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Finds behaviour that is <b>structurally unreachable through a direct method call</b> —
 * jtestforge-specification.md §7.5.
 *
 * <p>This is the answer to "which Spring tests are missing?", and it is a different
 * question from "which lines are uncovered". A handler invoked directly from a plain unit
 * test reports full line coverage while none of its actual contract has been exercised:
 * the framework, not the method body, implements that contract.
 *
 * <p>Every description produced here is a <b>behavioural statement</b>. The model is told
 * that an unauthorised request must be rejected and nothing proves it - never that an
 * annotation on some line needs an assertion. Instructing at the annotation level
 * produces tests coupled to the implementation rather than to the contract, the same
 * failure the mutant translation in §10.2 exists to avoid.
 *
 * <p>The gaps raised here are candidates. Whether a gap is genuinely open is settled by
 * {@link GapSuppressionDetector} against the existing test suite.
 */
public final class FrameworkSemanticGapScanner {

    public List<SemanticGap> scan(ProductionClass productionClass) {
        List<SemanticGap> gaps = new ArrayList<>();

        if (productionClass.hasAnyAnnotation(SpringAnnotations.CONFIGURATION_PROPERTIES)) {
            gaps.add(configurationPropertiesGap(productionClass));
        }
        boolean isRepository = productionClass.extendsAnyOf(SpringAnnotations.DATA_REPOSITORY_SUPERTYPES);

        for (ProductionMethod method : productionClass.methods()) {
            if (isRepository) {
                gaps.add(repositoryQueryGap(productionClass, method));
                continue;
            }
            gaps.addAll(webGapsFor(productionClass, method));
            securityGap(productionClass, method).ifPresent(gaps::add);
            transactionRollbackGap(productionClass, method).ifPresent(gaps::add);
            proxyBehaviourGap(productionClass, method).ifPresent(gaps::add);
        }
        return List.copyOf(gaps);
    }

    private List<SemanticGap> webGapsFor(ProductionClass productionClass, ProductionMethod method) {
        List<SemanticGap> gaps = new ArrayList<>();

        Optional<String> mappingAnnotation = RequestMappingReader.mappingAnnotationOf(method);
        if (mappingAnnotation.isPresent()) {
            String httpMethod = RequestMappingReader.httpMethodOf(method, mappingAnnotation.get());
            String path = RequestMappingReader.fullPathOf(productionClass, method, mappingAnnotation.get());

            gaps.add(new SemanticGap(SemanticGapKind.REQUEST_MAPPING, productionClass.fqn(), method.name(),
                    ("No test issues a %s request to %s, so the path this endpoint answers on, the HTTP "
                            + "method it accepts and the status it returns are all unverified.")
                            .formatted(httpMethod, path),
                    httpMethod, path, method.startLine()));

            if (hasBindingParameter(method)) {
                gaps.add(new SemanticGap(SemanticGapKind.REQUEST_BINDING, productionClass.fqn(), method.name(),
                        ("Nothing proves that the values in a %s request to %s are bound and converted into "
                                + "this method's arguments, nor what happens when one is missing or malformed.")
                                .formatted(httpMethod, path),
                        httpMethod, path, method.startLine()));
            }
            if (hasValidatedParameter(method)) {
                gaps.add(new SemanticGap(SemanticGapKind.BEAN_VALIDATION, productionClass.fqn(), method.name(),
                        ("No test sends an invalid payload to %s, so the declared constraints are never "
                                + "enforced against a real request and the rejection response body - which "
                                + "should say which constraint failed - is unverified.")
                                .formatted(path),
                        httpMethod, path, method.startLine()));
            }
        }

        if (method.hasAnyAnnotation(SpringAnnotations.EXCEPTION_HANDLER)) {
            gaps.add(new SemanticGap(SemanticGapKind.EXCEPTION_TRANSLATION, productionClass.fqn(), method.name(),
                    "Nothing proves that the failure this handler is responsible for is actually turned "
                            + "into the intended response status and body when it happens during a request.",
                    null, null, method.startLine()));
        }
        return gaps;
    }

    private Optional<SemanticGap> securityGap(ProductionClass productionClass, ProductionMethod method) {
        if (!method.hasAnyAnnotation(SpringAnnotations.METHOD_SECURITY)
                && !productionClass.hasAnyAnnotation(SpringAnnotations.METHOD_SECURITY)) {
            return Optional.empty();
        }
        Optional<String> mappingAnnotation = RequestMappingReader.mappingAnnotationOf(method);
        String httpMethod = mappingAnnotation.map(a -> RequestMappingReader.httpMethodOf(method, a)).orElse(null);
        String path = mappingAnnotation.map(a -> RequestMappingReader.fullPathOf(productionClass, method, a))
                .orElse(null);

        return Optional.of(new SemanticGap(SemanticGapKind.METHOD_SECURITY, productionClass.fqn(), method.name(),
                "Access to this operation is restricted, but nothing proves that an unauthorised caller "
                        + "is actually turned away, nor that a properly authorised one still gets through.",
                httpMethod, path, method.startLine()));
    }

    private Optional<SemanticGap> transactionRollbackGap(ProductionClass productionClass, ProductionMethod method) {
        if (!method.hasAnyAnnotation(SpringAnnotations.TRANSACTIONAL)) {
            return Optional.empty();
        }
        String attributes = transactionalAttributes(method);
        if (!declaresRollbackRule(attributes)) {
            // Plain @Transactional declares no behaviour of its own beyond Spring's
            // default, so there is nothing specific to this class left to verify.
            return Optional.empty();
        }
        return Optional.of(new SemanticGap(SemanticGapKind.TRANSACTION_ROLLBACK, productionClass.fqn(), method.name(),
                "This operation declares which failures must undo its work, but nothing proves the work "
                        + "is actually undone when such a failure occurs - a direct call cannot observe it.",
                null, null, method.startLine()));
    }

    private Optional<SemanticGap> proxyBehaviourGap(ProductionClass productionClass, ProductionMethod method) {
        if (!method.hasAnyAnnotation(SpringAnnotations.PROXY_BEHAVIOUR)) {
            return Optional.empty();
        }
        return Optional.of(new SemanticGap(SemanticGapKind.PROXY_BEHAVIOUR, productionClass.fqn(), method.name(),
                "This operation's caching, asynchrony, retrying or event delivery happens around the "
                        + "method rather than inside it, so calling the method directly cannot observe it and "
                        + "no test currently does.",
                null, null, method.startLine()));
    }

    private SemanticGap repositoryQueryGap(ProductionClass productionClass, ProductionMethod method) {
        return new SemanticGap(SemanticGapKind.REPOSITORY_QUERY, productionClass.fqn(), method.name(),
                "This query has no implementation to unit-test - what it returns is decided by the "
                        + "framework from its name or its declared query text, and nothing proves it returns "
                        + "what it claims against a real database.",
                null, null, method.startLine());
    }

    private SemanticGap configurationPropertiesGap(ProductionClass productionClass) {
        return new SemanticGap(SemanticGapKind.CONFIGURATION_PROPERTIES_BINDING, productionClass.fqn(), null,
                "Nothing proves that external configuration actually binds onto this type - including its "
                        + "defaults, relaxed name matching, and what happens when a supplied value violates a "
                        + "declared constraint. Constructing the object directly bypasses all of it.",
                null, null, 0);
    }

    private boolean hasBindingParameter(ProductionMethod method) {
        return method.parameters().stream()
                .anyMatch(parameter -> AnnotationNames.containsAny(
                        parameter.annotations(), SpringAnnotations.REQUEST_BINDING_PARAMETERS));
    }

    private boolean hasValidatedParameter(ProductionMethod method) {
        return method.parameters().stream().anyMatch(this::isValidated);
    }

    private boolean isValidated(ProductionParameter parameter) {
        return AnnotationNames.containsAny(parameter.annotations(), SpringAnnotations.VALIDATION);
    }

    private String transactionalAttributes(ProductionMethod method) {
        String attributes = method.annotationAttribute("Transactional");
        return attributes == null ? "" : attributes;
    }

    private boolean declaresRollbackRule(String attributes) {
        return attributes.contains("rollbackFor") || attributes.contains("noRollbackFor");
    }
}
