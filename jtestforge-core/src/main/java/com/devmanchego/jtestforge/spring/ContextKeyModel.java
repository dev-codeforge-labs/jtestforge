package com.devmanchego.jtestforge.spring;

import com.devmanchego.jtestforge.model.ContextKey;
import com.devmanchego.jtestforge.model.MockField;
import com.devmanchego.jtestforge.model.TestClassInfo;
import com.devmanchego.jtestforge.model.Tier;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Computes the cache key Spring's TestContext framework would compute for a test class —
 * jtestforge-specification.md §7.6.
 *
 * <p>Having this as a value lets two classes be compared for key equality before anything
 * runs, which is what turns "did that generated test just fork the context cache?" from a
 * question answerable only by timing a CI run into a check.
 *
 * <p>Read from the source text rather than from a loaded Spring context, because there is
 * no Spring here to ask - the target module's Spring is never on JTestForge's classpath.
 * The model therefore covers the key components that a test class declares in source and
 * that JTestForge can itself introduce: the slice annotation and its classes, profiles,
 * property sources, the mock-bean set, the web environment and initializers.
 */
public final class ContextKeyModel {

    private static final Pattern STRING_LITERAL = Pattern.compile("\"([^\"]*)\"");

    public ContextKey keyOf(TestClassInfo testClass, Tier tier) {
        return new ContextKey(
                tier,
                configurationClassesOf(testClass, tier),
                stringValuesOf(testClass, "ActiveProfiles"),
                stringValuesOf(testClass, "TestPropertySource"),
                mockBeanTypesOf(testClass),
                webEnvironmentOf(testClass),
                stringValuesOf(testClass, "ContextConfiguration"));
    }

    private List<String> configurationClassesOf(TestClassInfo testClass, Tier tier) {
        String sliceAnnotation = sliceAnnotationFor(tier);
        if (sliceAnnotation == null) {
            return List.of();
        }
        String attributes = testClass.classAnnotationAttribute(sliceAnnotation);
        if (attributes == null || attributes.isBlank()) {
            return List.of();
        }
        List<String> classes = new ArrayList<>();
        Matcher matcher = Pattern.compile("([A-Za-z_$][\\w$]*)\\s*\\.\\s*class").matcher(attributes);
        while (matcher.find()) {
            classes.add(matcher.group(1));
        }
        return classes;
    }

    private String sliceAnnotationFor(Tier tier) {
        return switch (tier) {
            case WEB_SLICE -> "WebMvcTest";
            case DATA_SLICE -> "DataJpaTest";
            case JSON_SLICE -> "JsonTest";
            case CONTEXT_SLICE -> "SpringBootTest";
            case PLAIN_UNIT -> null;
        };
    }

    /**
     * The mocked <em>types</em>, not the field names. Spring keys the context on the set
     * of bean overrides by type; two classes mocking the same type under different field
     * names share one context, and reporting a fork there would send the run chasing a
     * problem Spring does not have.
     */
    private Set<String> mockBeanTypesOf(TestClassInfo testClass) {
        Set<String> types = new LinkedHashSet<>();
        for (MockField field : testClass.mockFields()) {
            if (field.isSpringMockBean()) {
                types.add(field.typeName());
            }
        }
        return types;
    }

    private String webEnvironmentOf(TestClassInfo testClass) {
        String attributes = testClass.classAnnotationAttribute("SpringBootTest");
        if (attributes == null) {
            return "";
        }
        Matcher matcher = Pattern.compile("webEnvironment\\s*=\\s*(?:\\w+\\.)*(\\w+)").matcher(attributes);
        return matcher.find() ? matcher.group(1) : "";
    }

    /** Every string literal an annotation declares, in source order. */
    private List<String> stringValuesOf(TestClassInfo testClass, String annotationSimpleName) {
        String attributes = testClass.classAnnotationAttribute(annotationSimpleName);
        if (attributes == null) {
            return List.of();
        }
        if (attributes.isBlank()) {
            // Present but argument-less still differs from absent: the annotation itself
            // changes the key, so it must leave a trace.
            return List.of("@" + annotationSimpleName);
        }
        List<String> values = new ArrayList<>();
        Matcher matcher = STRING_LITERAL.matcher(attributes);
        while (matcher.find()) {
            values.add(matcher.group(1));
        }
        return values.isEmpty() ? List.of(attributes) : values;
    }
}
