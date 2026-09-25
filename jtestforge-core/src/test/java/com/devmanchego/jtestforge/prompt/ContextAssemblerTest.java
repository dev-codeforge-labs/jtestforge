package com.devmanchego.jtestforge.prompt;

import com.devmanchego.jtestforge.analysis.ProductionClassScanner;
import com.devmanchego.jtestforge.analysis.ProductionTypeSolvers;
import com.devmanchego.jtestforge.config.ContextConfig;
import com.devmanchego.jtestforge.model.ClassCoverage;
import com.devmanchego.jtestforge.model.LineStatus;
import com.devmanchego.jtestforge.model.MockBeanDeclaration;
import com.devmanchego.jtestforge.model.ProductionClass;
import com.devmanchego.jtestforge.model.ProductionMethod;
import com.devmanchego.jtestforge.model.SemanticVersion;
import com.devmanchego.jtestforge.model.SpringStackFacts;
import com.devmanchego.jtestforge.model.SpringStereotype;
import com.devmanchego.jtestforge.model.TestFrameworkVersions;
import com.github.javaparser.resolution.TypeSolver;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ContextAssemblerTest {

    private static List<ProductionClass> fixtureClasses;

    private final ContextAssembler assembler = new ContextAssembler(
            new ContextConfig(null, null, null, null, null, null, null));

    @BeforeAll
    static void scanFixture() throws URISyntaxException {
        Path mainSourceRoot = Path.of(ContextAssemblerTest.class.getClassLoader()
                .getResource("fixture-module").toURI()).resolve("src/main/java");
        TypeSolver typeSolver = ProductionTypeSolvers.forModule(mainSourceRoot, List.of());
        fixtureClasses = new ProductionClassScanner(typeSolver, mainSourceRoot).scan();
    }

    @Test
    void validationConstraintsListsOnlyRealConstraintsNotBindingAnnotations() {
        // @PathVariable and @RequestParam say how a value ARRIVES, not what makes it
        // valid. Listing them under "constraints" would tell the model that a binding
        // annotation is something to write a validation-rejection test for.
        String constraints = assembler.validationConstraints(fixtureClass("com.acme.web.OrderController"));

        assertThat(constraints).contains("jakarta.validation.Valid");
        assertThat(constraints).doesNotContain("PathVariable");
        assertThat(constraints).doesNotContain("RequestParam");
        assertThat(constraints).doesNotContain("RequestBody");
    }

    @Test
    void aClassWithNoValidatedParametersSaysSoInWords() {
        assertThat(assembler.validationConstraints(fixtureClass("com.acme.PaymentService")))
                .isEqualTo("_(none)_");
    }

    @Test
    void theStereotypeCarriesNoMarkupOfItsOwn() {
        // Formatting belongs to the template; a value that wraps itself in backticks
        // produces nested backticks wherever the template already does.
        assertThat(assembler.springStereotype(SpringStereotype.CONTROLLER)).isEqualTo("controller");
        assertThat(assembler.springStereotype(SpringStereotype.CONTROLLER_ADVICE))
                .isEqualTo("controller advice");
    }

    @Test
    void collaboratorsAreListedWithTheirResolvedTypes() {
        String collaborators = assembler.collaborators(fixtureClass("com.acme.PaymentService"));

        assertThat(collaborators).contains("`gateway`").contains("com.acme.PaymentGateway");
        assertThat(collaborators).contains("`logger`").contains("org.slf4j.Logger");
    }

    @Test
    void theTargetMethodSourceIsSlicedFromTheRealFileIncludingItsComments() {
        ProductionClass paymentService = fixtureClass("com.acme.PaymentService");
        ProductionMethod applyFee = method(paymentService, "applyFee");

        String source = assembler.targetMethodSource(paymentService, applyFee);

        assertThat(source).contains("public BigDecimal applyFee");
        assertThat(source).contains("amount must not be negative");
        assertThat(source).doesNotContain("public PaymentGateway gateway");
    }

    @Test
    void uncoveredLinesCarryTheirSourceTextNotJustLineNumbers() {
        ProductionClass paymentService = fixtureClass("com.acme.PaymentService");
        ProductionMethod applyFee = method(paymentService, "applyFee");
        ClassCoverage coverage = new ClassCoverage("com.acme.PaymentService", 0, 0, 0, 0, 0, 0,
                List.of(), Map.of(applyFee.startLine() + 1, new LineStatus(1, 0, 0, 0)));

        String uncovered = assembler.uncoveredLines(paymentService, applyFee, coverage);

        assertThat(uncovered).contains("line " + (applyFee.startLine() + 1));
        // A bare list of line numbers gives the model nothing to reason about.
        assertThat(uncovered).contains("if (amount.signum() < 0)");
    }

    /**
     * A method WorkUnitDiscovery selects purely for a missed branch has ZERO uncovered
     * lines - {@code uncoveredLines} alone would render "_(none)_" and leave the model
     * with no idea why it was even asked for a test.
     */
    @Test
    void uncoveredBranchesCarryHowManyOutcomesWereTakenAndTheSourceText() {
        ProductionClass paymentService = fixtureClass("com.acme.PaymentService");
        ProductionMethod applyFee = method(paymentService, "applyFee");
        int conditionLine = applyFee.startLine() + 1;
        ClassCoverage coverage = new ClassCoverage("com.acme.PaymentService", 0, 0, 0, 0, 1, 1,
                List.of(), Map.of(conditionLine, new LineStatus(0, 1, 1, 1)));

        String branches = assembler.uncoveredBranches(paymentService, applyFee, coverage);

        assertThat(branches).contains("line " + conditionLine)
                .contains("1 of 2 branch outcomes taken")
                .contains("if (amount.signum() < 0)");
    }

    @Test
    void aLineWithNoPartialBranchIsNotListedAsOne() {
        ProductionClass paymentService = fixtureClass("com.acme.PaymentService");
        ProductionMethod applyFee = method(paymentService, "applyFee");
        // Fully covered (both outcomes taken) and fully uncovered lines are each their
        // own, different, concern - neither belongs in the partial-branch section.
        ClassCoverage bothOutcomesTaken = new ClassCoverage("com.acme.PaymentService", 0, 0, 0, 0, 0, 2,
                List.of(), Map.of(applyFee.startLine() + 1, new LineStatus(0, 1, 0, 2)));
        ClassCoverage neverExecuted = new ClassCoverage("com.acme.PaymentService", 0, 0, 1, 0, 1, 0,
                List.of(), Map.of(applyFee.startLine() + 1, new LineStatus(1, 0, 1, 0)));

        assertThat(assembler.uncoveredBranches(paymentService, applyFee, bothOutcomesTaken)).isEqualTo("_(none)_");
        assertThat(assembler.uncoveredBranches(paymentService, applyFee, neverExecuted)).isEqualTo("_(none)_");
    }

    @Test
    void requestMappingsDescribeEachEndpointAndWhatItBinds() {
        String mappings = assembler.requestMappings(fixtureClass("com.acme.web.OrderController"));

        assertThat(mappings).contains("GET /api/orders/{id}").contains("findById");
        assertThat(mappings).contains("binding `id` from @PathVariable");
        assertThat(mappings).contains("POST /api/orders");
    }

    @Test
    void springContextNamesTheAnnotationToUseRatherThanTheVersionToInterpret() {
        // The model does not need to know that @MockBean became @MockitoBean in 6.2; it
        // needs to know which one compiles here.
        String springContext = assembler.springContext(bootThreeFour());

        assertThat(springContext).contains("@MockitoBean");
        assertThat(springContext).contains("jakarta.validation");
        assertThat(springContext).contains("H2");
    }

    @Test
    void springContextSaysWhenSecurityTestSupportIsAbsent() {
        SpringStackFacts noSecurityTest = new SpringStackFacts(true, true,
                new SemanticVersion(6, 2, 1), new SemanticVersion(3, 4, 1), true, true, false,
                SpringStackFacts.DetectedEmbeddedDatabase.NONE, SpringStackFacts.ValidationApi.JAKARTA,
                "org.springframework.test.context.bean.override.mockito.MockitoBean", true);

        assertThat(assembler.springContext(noSecurityTest)).contains("NOT available");
    }

    @Test
    void frameworkVersionsStateWhatIsAndIsNotOnTheClasspath() {
        String versions = assembler.frameworkVersions(new TestFrameworkVersions(
                new SemanticVersion(5, 10, 3), new SemanticVersion(5, 12, 0), true, false,
                new SemanticVersion(3, 26, 3), null));

        assertThat(versions).contains("JUnit Jupiter: `5.10.3`");
        assertThat(versions).contains("Hamcrest: not on the classpath");
        assertThat(versions).contains("`@ExtendWith(MockitoExtension.class)` is available");
        assertThat(versions).contains("static and final mocking (`mockStatic`) is NOT available");
    }

    @Test
    void theJavaLevelIsStatedTogetherWithWhatThatLevelLacks() {
        String java8 = assembler.frameworkVersions(TestFrameworkVersions.none().withJavaRelease(8));
        String java21 = assembler.frameworkVersions(TestFrameworkVersions.none().withJavaRelease(21));
        String unknown = assembler.frameworkVersions(TestFrameworkVersions.none());

        assertThat(java8).contains("Java language level: `8`").contains("`var`").contains("`List.of`").contains("records");
        assertThat(java21).contains("Java language level: `21`").doesNotContain("none of these exist");
        assertThat(unknown).doesNotContain("Java language level");
    }

    @Test
    void everyEmptySectionSaysSoInWordsRatherThanRenderingBlank() {
        assertThat(assembler.mockBeans(List.of())).isEqualTo("_(none)_");
        assertThat(assembler.frameworkSemanticGaps(List.of())).isEqualTo("_(none)_");
        assertThat(assembler.behaviourGaps(List.of())).isEqualTo("_(none)_");
        assertThat(assembler.compilerErrors(List.of())).isEqualTo("_(none)_");
        assertThat(assembler.testFailures(List.of())).isEqualTo("_(none)_");
        assertThat(assembler.existingTestClass(null)).isEqualTo("_(none)_");
        assertThat(assembler.existingTestNames(null)).isEqualTo("_(none)_");
    }

    @Test
    void mockBeansListNameAndType() {
        String mockBeans = assembler.mockBeans(List.of(
                new MockBeanDeclaration("orderService", "com.acme.OrderService")));

        assertThat(mockBeans).contains("`orderService`").contains("com.acme.OrderService");
    }

    private ProductionClass fixtureClass(String fqn) {
        return fixtureClasses.stream().filter(c -> c.fqn().equals(fqn)).findFirst()
                .orElseThrow(() -> new AssertionError("Not scanned: " + fqn));
    }

    private ProductionMethod method(ProductionClass productionClass, String name) {
        return productionClass.methods().stream().filter(m -> m.name().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("No such method: " + name));
    }

    private SpringStackFacts bootThreeFour() {
        return new SpringStackFacts(true, true, new SemanticVersion(6, 2, 1), new SemanticVersion(3, 4, 1),
                true, true, true, SpringStackFacts.DetectedEmbeddedDatabase.H2,
                SpringStackFacts.ValidationApi.JAKARTA,
                "org.springframework.test.context.bean.override.mockito.MockitoBean", true);
    }
}
