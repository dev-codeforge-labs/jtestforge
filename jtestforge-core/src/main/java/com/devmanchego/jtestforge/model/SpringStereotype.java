package com.devmanchego.jtestforge.model;

/**
 * The Spring role a production class plays, as far as tier selection is concerned —
 * jtestforge-specification.md §7.4.
 *
 * <p>Only distinctions that change the generated test's shape appear here. {@code @Service}
 * and {@code @Component} both collapse into {@link #PLAIN_BEAN}: neither needs a Spring
 * context to be unit-tested, so nothing downstream would ever branch on which of the two
 * it was.
 */
public enum SpringStereotype {
    /** {@code @Controller}, {@code @RestController}. */
    CONTROLLER,
    /** {@code @ControllerAdvice}, {@code @RestControllerAdvice}. */
    CONTROLLER_ADVICE,
    /** {@code @Repository}, or an interface extending a Spring Data repository. */
    REPOSITORY,
    /** {@code @Configuration}. */
    CONFIGURATION,
    /** {@code @ConfigurationProperties}. */
    CONFIGURATION_PROPERTIES,
    /** {@code @JsonComponent}, or a custom Jackson serializer/deserializer. */
    JSON_COMPONENT,
    /** {@code @Service}, {@code @Component}, or any other Spring-managed plain bean. */
    PLAIN_BEAN,
    /** No Spring stereotype at all - a plain domain class. */
    NONE
}
