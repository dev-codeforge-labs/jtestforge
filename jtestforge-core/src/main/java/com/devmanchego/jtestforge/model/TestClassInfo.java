package com.devmanchego.jtestforge.model;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * What an existing test class already contains — jtestforge-specification.md §7.2.
 *
 * <p>Read before anything is generated, for three separate purposes: to avoid proposing a
 * test that duplicates one already there, to tell the model which style to match, and to
 * recognise an existing Spring slice class by its annotations rather than by its filename.
 *
 * @param sourceFile        absolute path of the test file
 * @param className         simple name of the test class
 * @param packageName       its package, or {@code ""} for the default package
 * @param testMethodNames   names of every {@code @Test}/{@code @ParameterizedTest} method
 * @param mockFields        mock and spy fields the class declares
 * @param injectionStyle    how the class obtains its subject under test
 * @param assertionLibrary  which assertion library it already uses
 * @param classAnnotations  simple names of the class's own annotations - how a slice class
 *                          is identified, since a project may not follow the naming
 *                          convention JTestForge would have used
 * @param classAnnotationAttributes attribute text of the class's annotations, keyed by
 *                          simple name. Carries which production class a slice annotation
 *                          targets - the difference between {@code @WebMvcTest(OrderController.class)}
 *                          and {@code @WebMvcTest(CustomerController.class)}
 * @param importedTypes     every non-static imported type name, for import de-duplication
 * @param staticImports     every static import, likewise
 */
public record TestClassInfo(
        Path sourceFile,
        String className,
        String packageName,
        Set<String> testMethodNames,
        List<MockField> mockFields,
        InjectionStyle injectionStyle,
        AssertionLibrary assertionLibrary,
        List<String> classAnnotations,
        java.util.Map<String, String> classAnnotationAttributes,
        Set<String> importedTypes,
        Set<String> staticImports) {

    public TestClassInfo {
        Objects.requireNonNull(sourceFile, "sourceFile");
        Objects.requireNonNull(className, "className");
        packageName = packageName == null ? "" : packageName;
        testMethodNames = testMethodNames == null ? Set.of() : Set.copyOf(testMethodNames);
        mockFields = mockFields == null ? List.of() : List.copyOf(mockFields);
        injectionStyle = injectionStyle == null ? InjectionStyle.NONE : injectionStyle;
        assertionLibrary = assertionLibrary == null ? AssertionLibrary.NONE : assertionLibrary;
        classAnnotations = classAnnotations == null ? List.of() : List.copyOf(classAnnotations);
        classAnnotationAttributes = classAnnotationAttributes == null
                ? java.util.Map.of() : java.util.Map.copyOf(classAnnotationAttributes);
        importedTypes = importedTypes == null ? Set.of() : Set.copyOf(importedTypes);
        staticImports = staticImports == null ? Set.of() : Set.copyOf(staticImports);
    }

    public boolean hasTestMethod(String methodName) {
        return testMethodNames.contains(methodName);
    }

    public boolean hasClassAnnotation(String annotationName) {
        return AnnotationNames.contains(classAnnotations, annotationName);
    }

    /** Raw attribute text of a class annotation, keyed by its simple name. */
    public String classAnnotationAttribute(String annotationSimpleName) {
        return classAnnotationAttributes.get(annotationSimpleName);
    }

    /** Mock bean names, which for a Spring slice form part of the context cache key (§7.6). */
    public Set<String> springMockBeanNames() {
        return mockFields.stream()
                .filter(MockField::isSpringMockBean)
                .map(MockField::name)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
}
