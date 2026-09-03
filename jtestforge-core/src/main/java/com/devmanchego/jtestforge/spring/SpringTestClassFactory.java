package com.devmanchego.jtestforge.spring;

import com.devmanchego.jtestforge.model.MockBeanDeclaration;
import com.devmanchego.jtestforge.model.ProductionClass;
import com.devmanchego.jtestforge.model.SpringStackFacts;
import com.devmanchego.jtestforge.model.Tier;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;

/**
 * Renders the skeleton a Spring slice test class starts life as —
 * jtestforge-specification.md §7.2, §7.4.
 *
 * <p>The skeleton must compile before a single generated test is merged into it, which is
 * why every annotation and helper type it names comes from {@link SpringStackFacts} rather
 * than being hard-coded: {@code @MockBean} became {@code @MockitoBean} in Spring Framework
 * 6.2 / Boot 3.4, and emitting the wrong one is a guaranteed compilation failure on every
 * class, for every test, that no repair loop should have to absorb.
 *
 * <p>A skeleton also never declares anything that would fork the context cache key (§7.6):
 * no {@code @DirtiesContext}, no {@code @TestPropertySource}, no {@code @ActiveProfiles},
 * and no web environment. The mock-bean set is the only key component it contributes, and
 * it is computed once, up front, from the class's collaborators.
 */
public final class SpringTestClassFactory {

    private static final String INDENT = "    ";

    private final SpringStackFacts facts;
    private final MockBeanSetResolver mockBeanSetResolver;

    public SpringTestClassFactory(SpringStackFacts facts, MockBeanSetResolver mockBeanSetResolver) {
        this.facts = Objects.requireNonNull(facts, "facts");
        this.mockBeanSetResolver = Objects.requireNonNull(mockBeanSetResolver, "mockBeanSetResolver");
    }

    /** {@code PLAIN_UNIT} is not a Spring slice and has no skeleton here. */
    public boolean supports(Tier tier) {
        return tier != Tier.PLAIN_UNIT;
    }

    public String renderSkeleton(ProductionClass productionClass, Tier tier, String testClassName) {
        if (!supports(tier)) {
            throw new IllegalArgumentException(
                    "PLAIN_UNIT is not a Spring slice; it has no Spring skeleton");
        }
        return switch (tier) {
            case WEB_SLICE -> renderWebSlice(productionClass, testClassName);
            case DATA_SLICE -> renderDataSlice(productionClass, testClassName);
            case JSON_SLICE -> renderJsonSlice(productionClass, testClassName);
            case CONTEXT_SLICE -> renderContextSlice(productionClass, testClassName);
            case PLAIN_UNIT -> throw new IllegalStateException("unreachable");
        };
    }

    private String renderWebSlice(ProductionClass productionClass, String testClassName) {
        String subject = productionClass.simpleName();
        List<MockBeanDeclaration> mockBeans = mockBeanSetResolver.resolve(productionClass);

        TreeSet<String> imports = new TreeSet<>(List.of(
                "org.springframework.beans.factory.annotation.Autowired",
                "org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest",
                "org.springframework.test.web.servlet.MockMvc"));
        if (!mockBeans.isEmpty()) {
            imports.add(facts.mockBeanAnnotationFqn());
        }
        mockBeans.stream().map(MockBeanDeclaration::typeName)
                .filter(this::isImportable)
                .forEach(imports::add);

        List<String> body = new ArrayList<>();
        body.add(INDENT + "@Autowired");
        body.add(INDENT + "private MockMvc mockMvc;");
        for (MockBeanDeclaration mockBean : mockBeans) {
            body.add("");
            body.add(INDENT + "@" + mockBeanAnnotationSimpleName());
            body.add(INDENT + "private " + mockBean.simpleTypeName() + " " + mockBean.name() + ";");
        }

        return render(productionClass.packageName(), imports,
                "@WebMvcTest(" + subject + ".class)", testClassName, body);
    }

    private String renderDataSlice(ProductionClass productionClass, String testClassName) {
        String subject = productionClass.simpleName();
        TreeSet<String> imports = new TreeSet<>(List.of(
                "org.springframework.beans.factory.annotation.Autowired",
                "org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest",
                "org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager"));

        List<String> body = List.of(
                INDENT + "@Autowired",
                INDENT + "private TestEntityManager entityManager;",
                "",
                INDENT + "@Autowired",
                INDENT + "private " + subject + " " + decapitalise(subject) + ";");

        return render(productionClass.packageName(), imports, "@DataJpaTest", testClassName, body);
    }

    private String renderJsonSlice(ProductionClass productionClass, String testClassName) {
        String subject = productionClass.simpleName();
        TreeSet<String> imports = new TreeSet<>(List.of(
                "org.springframework.beans.factory.annotation.Autowired",
                "org.springframework.boot.test.autoconfigure.json.JsonTest",
                "org.springframework.boot.test.json.JacksonTester"));

        List<String> body = List.of(
                INDENT + "@Autowired",
                INDENT + "private JacksonTester<" + subject + "> json;");

        return render(productionClass.packageName(), imports, "@JsonTest", testClassName, body);
    }

    private String renderContextSlice(ProductionClass productionClass, String testClassName) {
        String subject = productionClass.simpleName();
        TreeSet<String> imports = new TreeSet<>(List.of(
                "org.springframework.beans.factory.annotation.Autowired",
                "org.springframework.boot.test.context.SpringBootTest",
                "org.springframework.context.ApplicationContext"));

        List<String> body = List.of(
                INDENT + "@Autowired",
                INDENT + "private ApplicationContext applicationContext;");

        // Restricted to the smallest class set that reproduces the wiring under test
        // (§7.4). A bare @SpringBootTest would load the entire application.
        return render(productionClass.packageName(), imports,
                "@SpringBootTest(classes = " + subject + ".class)", testClassName, body);
    }

    /**
     * {@code org.junit.jupiter.api.Test} is added to every skeleton, unconditionally.
     * These imports otherwise cover only the fields the skeleton itself declares - but the
     * whole purpose of a skeleton is to receive {@code @Test} methods, and the response
     * contract guarantees that every merged declaration is one (§6.2 rule 3). Without it,
     * the first merge into every newly created slice class fails to compile for a reason
     * that has nothing to do with what the model wrote. Found end to end in phase 18.
     */
    private String render(String packageName, TreeSet<String> imports,
                          String classAnnotation, String testClassName, List<String> body) {
        imports.add("org.junit.jupiter.api.Test");
        StringBuilder source = new StringBuilder();
        if (!packageName.isEmpty()) {
            source.append("package ").append(packageName).append(";\n\n");
        }
        for (String importedType : imports) {
            source.append("import ").append(importedType).append(";\n");
        }
        source.append('\n');
        source.append(classAnnotation).append('\n');
        source.append("class ").append(testClassName).append(" {\n\n");
        for (String line : body) {
            source.append(line).append('\n');
        }
        source.append("}\n");
        return source.toString();
    }

    private String mockBeanAnnotationSimpleName() {
        String fqn = facts.mockBeanAnnotationFqn();
        return fqn.substring(fqn.lastIndexOf('.') + 1);
    }

    /**
     * Only fully-qualified collaborator types can be imported. A type that failed
     * resolution is recorded as written (see {@code TypeResolution}); emitting an import
     * for a bare name would not compile.
     */
    private boolean isImportable(String typeName) {
        return typeName.contains(".") && !typeName.startsWith("java.lang.");
    }

    private String decapitalise(String name) {
        return name.isEmpty() ? name : Character.toLowerCase(name.charAt(0)) + name.substring(1);
    }
}
