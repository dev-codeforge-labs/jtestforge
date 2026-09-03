package com.devmanchego.jtestforge.prompt;

import com.devmanchego.jtestforge.analysis.ProductionClassScanner;
import com.devmanchego.jtestforge.analysis.ProductionTypeSolvers;
import com.devmanchego.jtestforge.config.ContextConfig;
import com.devmanchego.jtestforge.model.MockBeanDeclaration;
import com.devmanchego.jtestforge.model.ProductionClass;
import com.devmanchego.jtestforge.model.ProductionMethod;
import com.devmanchego.jtestforge.model.SemanticGap;
import com.devmanchego.jtestforge.model.SemanticVersion;
import com.devmanchego.jtestforge.model.SpringStackFacts;
import com.devmanchego.jtestforge.model.SpringStereotype;
import com.devmanchego.jtestforge.model.TestFrameworkVersions;
import com.devmanchego.jtestforge.spring.FrameworkSemanticGapScanner;
import com.devmanchego.jtestforge.spring.MockBeanSetResolver;
import com.github.javaparser.resolution.TypeSolver;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Renders real prompts from the real fixture module — jtestforge-implementation-plan.md
 * phase 10's "snapshot tests of each rendered prompt against the fixture module, one per
 * tier".
 *
 * <p>Asserted structurally rather than against golden files: what matters is that no
 * placeholder survives unsubstituted and that each prompt actually carries the specific
 * context its template promised. A byte-exact golden file would fail on every wording
 * tweak - and the templates are expected to be tuned repeatedly (the plan budgets two or
 * three tuning passes after phase 18), so a test that punishes editing them would get
 * deleted rather than maintained.
 */
class RenderedPromptTest {

    private static List<ProductionClass> fixtureClasses;
    private static Map<PromptTemplateId, PromptTemplate> templates;

    private final ContextAssembler assembler = new ContextAssembler(defaultContextConfig());
    private final PromptRenderer renderer = new PromptRenderer(60_000);
    private final FrameworkSemanticGapScanner gapScanner = new FrameworkSemanticGapScanner();

    @BeforeAll
    static void scanFixtureAndLoadTemplates() throws URISyntaxException {
        Path fixtureModule = Path.of(RenderedPromptTest.class.getClassLoader()
                .getResource("fixture-module").toURI());
        Path mainSourceRoot = fixtureModule.resolve("src/main/java");
        TypeSolver typeSolver = ProductionTypeSolvers.forModule(mainSourceRoot, List.of());
        fixtureClasses = new ProductionClassScanner(typeSolver, mainSourceRoot).scan();
        templates = new PromptTemplateLoader().loadBundled();
    }

    @Test
    void thePlainUnitPromptCarriesTheMethodItsClassAndTheRules() {
        ProductionClass paymentService = fixtureClass("com.acme.PaymentService");
        ProductionMethod applyFee = method(paymentService, "applyFee");

        String prompt = renderer.render(templates.get(PromptTemplateId.NEW_TEST_CLASS),
                PromptContext.builder()
                        .with(PromptPlaceholder.CLASS_FQN, paymentService.fqn())
                        .with(PromptPlaceholder.TARGET_METHOD, applyFee.signature())
                        .with(PromptPlaceholder.TARGET_METHOD_SOURCE,
                                assembler.targetMethodSource(paymentService, applyFee))
                        .with(PromptPlaceholder.CLASS_SOURCE, assembler.classSource(paymentService))
                        .with(PromptPlaceholder.COLLABORATORS, assembler.collaborators(paymentService))
                        .with(PromptPlaceholder.FRAMEWORK_VERSIONS,
                                assembler.frameworkVersions(frameworkVersions()))
                        .with(PromptPlaceholder.RULES, templates.get(PromptTemplateId.RULES).rawText())
                        .build());

        assertNoPlaceholdersRemain(prompt);
        assertThat(prompt).contains("com.acme.PaymentService");
        // The real method body, sliced from the real file.
        assertThat(prompt).contains("amount must not be negative");
        // The real collaborators, resolved by the real symbol solver.
        assertThat(prompt).contains("com.acme.PaymentGateway");
        assertThat(prompt).contains("org.slf4j.Logger");
        // The rules block, inlined.
        assertThat(prompt).contains("Would this test still pass if the method under test were deliberately broken?");
    }

    @Test
    void theWebSlicePromptCarriesTheRealEndpointsGapsAndMockBeans() {
        ProductionClass controller = fixtureClass("com.acme.web.OrderController");
        List<SemanticGap> gaps = gapScanner.scan(controller);
        List<MockBeanDeclaration> mockBeans = new MockBeanSetResolver().resolve(controller);

        String prompt = renderer.render(templates.get(PromptTemplateId.WEB_SLICE_TESTS),
                webSliceContext(controller, gaps, mockBeans));

        assertNoPlaceholdersRemain(prompt);
        // Real mappings, read from the real annotations.
        assertThat(prompt).contains("GET /api/orders/{id}");
        assertThat(prompt).contains("POST /api/orders");
        // Real semantic gaps, phrased behaviourally (§7.5).
        assertThat(prompt).contains("unauthorised");
        assertThat(prompt).doesNotContain("@PreAuthorize annotation on line");
        // Real exception handler.
        assertThat(prompt).contains("handleIllegalState");
        // Both rules blocks.
        assertThat(prompt).contains("Never change the class's shape");
        assertThat(prompt).contains("Response format");
    }

    @Test
    void theWebSlicePromptNamesTheAnnotationsThisSpringVersionActuallyHas() {
        ProductionClass controller = fixtureClass("com.acme.web.OrderController");

        String prompt = renderer.render(templates.get(PromptTemplateId.WEB_SLICE_TESTS),
                webSliceContext(controller, gapScanner.scan(controller),
                        new MockBeanSetResolver().resolve(controller)));

        assertThat(prompt).contains("@MockitoBean");
        assertThat(prompt).doesNotContain("org.springframework.boot.test.mock.mockito.MockBean");
    }

    @Test
    void theJsonSlicePromptCarriesTheConfigurationPropertiesGap() {
        ProductionClass properties = fixtureClass("com.acme.config.BillingProperties");
        List<SemanticGap> gaps = gapScanner.scan(properties);

        String prompt = renderer.render(templates.get(PromptTemplateId.JSON_SLICE_TESTS),
                PromptContext.builder()
                        .with(PromptPlaceholder.CLASS_FQN, properties.fqn())
                        .with(PromptPlaceholder.CLASS_SOURCE, assembler.classSource(properties))
                        .with(PromptPlaceholder.VALIDATION_CONSTRAINTS,
                                assembler.validationConstraints(properties))
                        .with(PromptPlaceholder.FRAMEWORK_SEMANTIC_GAPS,
                                assembler.frameworkSemanticGaps(gaps))
                        .with(PromptPlaceholder.EXISTING_TEST_CLASS, "_(none)_")
                        .with(PromptPlaceholder.EXISTING_TEST_NAMES, "_(none)_")
                        .with(PromptPlaceholder.SPRING_CONTEXT, assembler.springContext(bootThreeFour()))
                        .with(PromptPlaceholder.SPRING_RULES,
                                templates.get(PromptTemplateId.SPRING_RULES).rawText())
                        .with(PromptPlaceholder.RULES, templates.get(PromptTemplateId.RULES).rawText())
                        .build());

        assertNoPlaceholdersRemain(prompt);
        assertThat(prompt).contains("com.acme.config.BillingProperties");
        assertThat(prompt).contains("binds");
    }

    @Test
    void theRepairPromptsCarryTheirDiagnosticsAndTheRules() {
        ProductionClass paymentService = fixtureClass("com.acme.PaymentService");

        String prompt = renderer.render(templates.get(PromptTemplateId.FIX_COMPILATION),
                PromptContext.builder()
                        .with(PromptPlaceholder.CLASS_FQN, paymentService.fqn())
                        .with(PromptPlaceholder.TIER, "PLAIN_UNIT")
                        .with(PromptPlaceholder.CLASS_SOURCE, assembler.classSource(paymentService))
                        .with(PromptPlaceholder.COLLABORATORS, assembler.collaborators(paymentService))
                        .with(PromptPlaceholder.COMPILER_ERRORS, assembler.compilerErrors(List.of(
                                new com.devmanchego.jtestforge.model.CompilerError(
                                        "PaymentServiceTest.java", 42, 9,
                                        "cannot find symbol\n  symbol: variable gateway"))))
                        .with(PromptPlaceholder.FRAMEWORK_VERSIONS,
                                assembler.frameworkVersions(frameworkVersions()))
                        .with(PromptPlaceholder.RULES, templates.get(PromptTemplateId.RULES).rawText())
                        .build());

        assertNoPlaceholdersRemain(prompt);
        assertThat(prompt).contains("cannot find symbol");
        assertThat(prompt).contains("PaymentServiceTest.java:42:9");
    }

    @Test
    void everyBundledGenerationTemplateRendersWithAFullyPopulatedContext() {
        // Guards against a template gaining a placeholder that nothing ever supplies -
        // which would only surface at run time, mid-generation, on a real unit.
        ProductionClass controller = fixtureClass("com.acme.web.OrderController");
        PromptContext everything = fullyPopulatedContext(controller);

        for (PromptTemplateId id : PromptTemplateId.values()) {
            if (id.isSharedRulesBlock()) {
                continue;
            }
            String prompt = renderer.render(templates.get(id), everything);
            assertNoPlaceholdersRemain(prompt);
        }
    }

    // --- fixtures ---------------------------------------------------------------------

    private PromptContext webSliceContext(
            ProductionClass controller, List<SemanticGap> gaps, List<MockBeanDeclaration> mockBeans) {
        return PromptContext.builder()
                .with(PromptPlaceholder.CLASS_FQN, controller.fqn())
                .with(PromptPlaceholder.CLASS_SOURCE, assembler.classSource(controller))
                .with(PromptPlaceholder.SPRING_STEREOTYPE,
                        assembler.springStereotype(SpringStereotype.CONTROLLER))
                .with(PromptPlaceholder.REQUEST_MAPPINGS, assembler.requestMappings(controller))
                .with(PromptPlaceholder.VALIDATION_CONSTRAINTS, assembler.validationConstraints(controller))
                .with(PromptPlaceholder.SECURITY_ANNOTATIONS, assembler.securityAnnotations(controller))
                .with(PromptPlaceholder.EXCEPTION_HANDLERS, assembler.exceptionHandlers(controller))
                .with(PromptPlaceholder.MOCK_BEANS, assembler.mockBeans(mockBeans))
                .with(PromptPlaceholder.FRAMEWORK_SEMANTIC_GAPS, assembler.frameworkSemanticGaps(gaps))
                .with(PromptPlaceholder.EXISTING_TEST_CLASS, "_(none)_")
                .with(PromptPlaceholder.EXISTING_TEST_NAMES, "_(none)_")
                .with(PromptPlaceholder.SPRING_CONTEXT, assembler.springContext(bootThreeFour()))
                .with(PromptPlaceholder.SPRING_RULES, templates.get(PromptTemplateId.SPRING_RULES).rawText())
                .with(PromptPlaceholder.RULES, templates.get(PromptTemplateId.RULES).rawText())
                .build();
    }

    private PromptContext fullyPopulatedContext(ProductionClass productionClass) {
        PromptContext.Builder builder = PromptContext.builder();
        for (PromptPlaceholder placeholder : PromptPlaceholder.values()) {
            builder.with(placeholder, "value-for-" + placeholder.name());
        }
        return builder
                .with(PromptPlaceholder.CLASS_FQN, productionClass.fqn())
                .with(PromptPlaceholder.RULES, templates.get(PromptTemplateId.RULES).rawText())
                .with(PromptPlaceholder.SPRING_RULES, templates.get(PromptTemplateId.SPRING_RULES).rawText())
                .build();
    }

    private void assertNoPlaceholdersRemain(String prompt) {
        assertThat(prompt).as("rendered prompt still contains an unsubstituted placeholder")
                .doesNotContainPattern("[{][{][A-Z_]+[}][}]");
    }

    private ProductionClass fixtureClass(String fqn) {
        return fixtureClasses.stream().filter(c -> c.fqn().equals(fqn)).findFirst()
                .orElseThrow(() -> new AssertionError("Not scanned: " + fqn));
    }

    private ProductionMethod method(ProductionClass productionClass, String name) {
        return productionClass.methods().stream().filter(m -> m.name().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("No such method: " + name));
    }

    private static ContextConfig defaultContextConfig() {
        return new ContextConfig(null, null, null, null, null, null, null);
    }

    private TestFrameworkVersions frameworkVersions() {
        return new TestFrameworkVersions(new SemanticVersion(5, 10, 3), new SemanticVersion(5, 12, 0),
                true, false, new SemanticVersion(3, 26, 3), null);
    }

    private SpringStackFacts bootThreeFour() {
        return new SpringStackFacts(true, true, new SemanticVersion(6, 2, 1), new SemanticVersion(3, 4, 1),
                true, true, true, SpringStackFacts.DetectedEmbeddedDatabase.H2,
                SpringStackFacts.ValidationApi.JAKARTA,
                "org.springframework.test.context.bean.override.mockito.MockitoBean", true);
    }
}
