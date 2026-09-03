package com.devmanchego.jtestforge.model;

/**
 * The test-framework versions actually on the target module's test classpath —
 * jtestforge-specification.md §7.3. Rendered into {@code {{FRAMEWORK_VERSIONS}}}.
 *
 * <p>This matters more than it looks: {@code mockStatic} needs mockito-inline on older
 * Mockito, {@code @ExtendWith(MockitoExtension.class)} needs mockito-junit-jupiter, and a
 * model that assumes a newer API than the project has produces code that cannot compile.
 * Telling it what is actually there removes a whole class of wasted repair attempts.
 *
 * <p>A {@code null} version means the library was not found at all.
 */
public record TestFrameworkVersions(
        SemanticVersion junitJupiter,
        SemanticVersion mockito,
        boolean mockitoJUnitJupiterPresent,
        boolean mockitoInlinePresent,
        SemanticVersion assertJ,
        SemanticVersion hamcrest) {

    public static TestFrameworkVersions none() {
        return new TestFrameworkVersions(null, null, false, false, null, null);
    }
}
