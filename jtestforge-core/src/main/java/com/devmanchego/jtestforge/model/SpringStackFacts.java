package com.devmanchego.jtestforge.model;

import java.util.Objects;

/**
 * What the target module's dependency list says about its Spring stack —
 * jtestforge-specification.md §7.3. Rendered into {@code {{SPRING_CONTEXT}}} so the model
 * is told the exact annotations and helper types that actually exist on this module's
 * classpath.
 *
 * <p>Getting this wrong produces a 100% compilation-failure rate that no repair loop
 * should have to absorb (§2): {@code @MockBean} became {@code @MockitoBean} in Spring
 * Framework 6.2 / Boot 3.4, and {@code MockMvcTester} exists only from Boot 3.4.
 *
 * @param springTestPresent      {@code spring-test} is on the test classpath at all
 * @param bootTestPresent        Spring Boot's test support is present - required for the
 *                               slice annotations, which are Boot's, not plain Spring's
 * @param frameworkVersion       resolved Spring Framework version, or {@code null}
 * @param bootVersion            resolved Spring Boot version, or {@code null}
 * @param webMvcPresent          Spring MVC is on the classpath
 * @param dataJpaPresent         Spring Data JPA is on the classpath
 * @param securityTestPresent    {@code spring-security-test} is available
 * @param embeddedDatabase       the embedded database detected on the test classpath
 * @param validationApi          which Bean Validation API the module uses
 * @param mockBeanAnnotationFqn  the mock-bean annotation to generate against
 * @param mockMvcTesterAvailable whether {@code MockMvcTester} may be used
 */
public record SpringStackFacts(
        boolean springTestPresent,
        boolean bootTestPresent,
        SemanticVersion frameworkVersion,
        SemanticVersion bootVersion,
        boolean webMvcPresent,
        boolean dataJpaPresent,
        boolean securityTestPresent,
        DetectedEmbeddedDatabase embeddedDatabase,
        ValidationApi validationApi,
        String mockBeanAnnotationFqn,
        boolean mockMvcTesterAvailable) {

    /** Which embedded database, if any, {@code @DataJpaTest} could run against. */
    public enum DetectedEmbeddedDatabase {
        NONE, H2, HSQLDB, DERBY
    }

    /** Which Bean Validation API the module's constraints come from. */
    public enum ValidationApi {
        NONE, JAKARTA, JAVAX
    }

    public SpringStackFacts {
        Objects.requireNonNull(embeddedDatabase, "embeddedDatabase");
        Objects.requireNonNull(validationApi, "validationApi");
        Objects.requireNonNull(mockBeanAnnotationFqn, "mockBeanAnnotationFqn");
    }

    /** Facts for a module with no Spring at all: only the plain unit tier applies. */
    public static SpringStackFacts noSpring() {
        return new SpringStackFacts(false, false, null, null, false, false, false,
                DetectedEmbeddedDatabase.NONE, ValidationApi.NONE,
                "org.springframework.boot.test.mock.mockito.MockBean", false);
    }

    public boolean hasEmbeddedDatabase() {
        return embeddedDatabase != DetectedEmbeddedDatabase.NONE;
    }
}
