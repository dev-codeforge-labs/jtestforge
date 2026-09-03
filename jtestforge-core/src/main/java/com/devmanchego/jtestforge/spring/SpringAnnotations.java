package com.devmanchego.jtestforge.spring;

import java.util.List;
import java.util.Set;

/**
 * The annotation and supertype names that carry Spring meaning for tier classification
 * (§7.4) and semantic-gap detection (§7.5).
 *
 * <p>Fully-qualified names are listed, but matching accepts simple names too - see
 * {@code AnnotationNames}. A target project reached through a wildcard import leaves only
 * the simple name resolvable, and missing a real {@code @PreAuthorize} because of an
 * import style would leave a security contract silently unverified.
 */
final class SpringAnnotations {

    static final List<String> CONTROLLER = List.of(
            "org.springframework.stereotype.Controller",
            "org.springframework.web.bind.annotation.RestController");

    static final List<String> CONTROLLER_ADVICE = List.of(
            "org.springframework.web.bind.annotation.ControllerAdvice",
            "org.springframework.web.bind.annotation.RestControllerAdvice");

    static final List<String> REPOSITORY = List.of("org.springframework.stereotype.Repository");

    static final List<String> CONFIGURATION = List.of(
            "org.springframework.context.annotation.Configuration");

    static final List<String> CONFIGURATION_PROPERTIES = List.of(
            "org.springframework.boot.context.properties.ConfigurationProperties");

    static final List<String> JSON_COMPONENT = List.of(
            "org.springframework.boot.jackson.JsonComponent");

    static final List<String> PLAIN_BEAN = List.of(
            "org.springframework.stereotype.Service",
            "org.springframework.stereotype.Component");

    static final List<String> REQUEST_MAPPINGS = List.of(
            "org.springframework.web.bind.annotation.RequestMapping",
            "org.springframework.web.bind.annotation.GetMapping",
            "org.springframework.web.bind.annotation.PostMapping",
            "org.springframework.web.bind.annotation.PutMapping",
            "org.springframework.web.bind.annotation.DeleteMapping",
            "org.springframework.web.bind.annotation.PatchMapping");

    static final List<String> REQUEST_BINDING_PARAMETERS = List.of(
            "org.springframework.web.bind.annotation.PathVariable",
            "org.springframework.web.bind.annotation.RequestParam",
            "org.springframework.web.bind.annotation.RequestBody",
            "org.springframework.web.bind.annotation.RequestHeader");

    static final List<String> VALIDATION = List.of(
            "jakarta.validation.Valid",
            "javax.validation.Valid",
            "org.springframework.validation.annotation.Validated");

    static final List<String> EXCEPTION_HANDLER = List.of(
            "org.springframework.web.bind.annotation.ExceptionHandler");

    static final List<String> METHOD_SECURITY = List.of(
            "org.springframework.security.access.prepost.PreAuthorize",
            "org.springframework.security.access.prepost.PostAuthorize",
            "org.springframework.security.access.annotation.Secured",
            "jakarta.annotation.security.RolesAllowed",
            "javax.annotation.security.RolesAllowed");

    static final List<String> TRANSACTIONAL = List.of(
            "org.springframework.transaction.annotation.Transactional",
            "jakarta.transaction.Transactional");

    static final List<String> PROXY_BEHAVIOUR = List.of(
            "org.springframework.cache.annotation.Cacheable",
            "org.springframework.cache.annotation.CacheEvict",
            "org.springframework.scheduling.annotation.Async",
            "org.springframework.retry.annotation.Retryable",
            "org.springframework.context.event.EventListener",
            "org.springframework.transaction.event.TransactionalEventListener");

    /** Spring Data base interfaces, matched on simple name (see class Javadoc). */
    static final Set<String> DATA_REPOSITORY_SUPERTYPES = Set.of(
            "Repository", "CrudRepository", "ListCrudRepository",
            "PagingAndSortingRepository", "ListPagingAndSortingRepository",
            "JpaRepository", "MongoRepository", "ReactiveCrudRepository", "R2dbcRepository");

    private SpringAnnotations() {
    }
}
