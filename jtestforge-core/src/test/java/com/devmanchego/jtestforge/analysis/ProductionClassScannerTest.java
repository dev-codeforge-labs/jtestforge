package com.devmanchego.jtestforge.analysis;

import com.devmanchego.jtestforge.model.Collaborator;
import com.devmanchego.jtestforge.model.ProductionClass;
import com.devmanchego.jtestforge.model.ProductionMethod;
import com.devmanchego.jtestforge.model.Visibility;
import com.github.javaparser.resolution.TypeSolver;
import org.junit.jupiter.api.Test;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * Integration test against the fixture module's real {@code src/main/java} tree
 * (jtestforge-core/src/test/resources/fixture-module), described in
 * jtestforge-implementation-plan.md's "General testing principles". Real parsing, real
 * symbol resolution - no mocked AST.
 */
class ProductionClassScannerTest {

    @Test
    void scansExactlyTheConcreteClassesAndSkipsEverythingStructurallyUntestable() throws URISyntaxException {
        List<ProductionClass> classes = scanFixtureModule();

        assertThat(classes).extracting(ProductionClass::fqn).containsExactlyInAnyOrder(
                "com.acme.PaymentService",
                "com.acme.CustomerEntity",
                "com.acme.ReportSettings",
                "com.acme.LedgerPoster",
                "com.acme.BillingService",
                "com.acme.config.BillingProperties",
                "com.acme.config.CacheConfig",
                "com.acme.web.OrderController");
        // Never scanned: PaymentGateway and OrderRepository (interfaces), AbstractLedger
        // (abstract), CurrencyCode (enum), and OrderController.CreateOrderRequest (a
        // nested record) - all excluded structurally, by node kind or explicit check,
        // not by any configurable selection rule.
    }

    @Test
    void modernLanguageConstructsParseRatherThanFailingTheScan() throws URISyntaxException {
        // OrderController declares a nested record. JavaParser's default language level
        // predates records, so an unset level turns a perfectly valid modern module into
        // a parse failure - the scan below simply completing is the assertion.
        assertThat(scanFixtureModule()).isNotEmpty();
    }

    @Test
    void aClassWhoseCollaboratorTypeIsOnlyOnTheCompileClasspathStillResolves() throws URISyntaxException {
        ProductionClass paymentService = classNamed(scanFixtureModule(), "com.acme.PaymentService");

        // org.slf4j.Logger is not part of the fixture module's own source - it resolves
        // only because ReflectionTypeSolver sees it on the running JVM's classpath,
        // standing in for "a real dependency jar resolved via mvn dependency:build-classpath".
        assertThat(paymentService.collaborators())
                .extracting(Collaborator::name, Collaborator::typeFqn)
                .containsExactlyInAnyOrder(
                        tuple("gateway", "com.acme.PaymentGateway"),
                        tuple("logger", "org.slf4j.Logger"));
    }

    @Test
    void computesApproximateComplexityAndLineRangeForEachMethod() throws URISyntaxException {
        ProductionClass paymentService = classNamed(scanFixtureModule(), "com.acme.PaymentService");

        ProductionMethod applyFee = methodNamed(paymentService, "applyFee");
        // base 1 + the negative-amount guard + the fee-tier if/else = 3
        assertThat(applyFee.cyclomaticComplexity()).isEqualTo(3);
        assertThat(applyFee.parameterTypes()).containsExactly("java.math.BigDecimal", "java.util.Currency");
        assertThat(applyFee.visibility()).isEqualTo(Visibility.PUBLIC);
        assertThat(applyFee.startLine()).isLessThan(applyFee.endLine());

        ProductionMethod gateway = methodNamed(paymentService, "gateway");
        assertThat(gateway.cyclomaticComplexity()).isEqualTo(1);
    }

    @Test
    void theJpaEntityIsRecordedWithItsResolvedAnnotationFqn() throws URISyntaxException {
        ProductionClass entity = classNamed(scanFixtureModule(), "com.acme.CustomerEntity");

        // The annotation resolves via the import statement alone - jakarta.persistence
        // is not, and must not need to be, an actual dependency of jtestforge itself.
        assertThat(entity.hasAnnotation("jakarta.persistence.Entity")).isTrue();
    }

    @Test
    void aLombokDataClassHasNoMethodsBecauseJavaParserNeverRunsAnnotationProcessing() throws URISyntaxException {
        ProductionClass reportSettings = classNamed(scanFixtureModule(), "com.acme.ReportSettings");

        // Lombok would generate getTitle()/setTitle()/isIncludeCharts()/... at compile
        // time. None of that exists in the source text, so none of it may appear here -
        // a scanner that somehow "saw" them would be hallucinating, not scanning.
        assertThat(reportSettings.methods()).isEmpty();
    }

    @Test
    void fieldInjectionIsFoundEvenWithoutAConstructor() throws URISyntaxException {
        ProductionClass ledgerPoster = classNamed(scanFixtureModule(), "com.acme.LedgerPoster");

        assertThat(ledgerPoster.collaborators())
                .extracting(Collaborator::name)
                .containsExactly("logger");
    }

    private ProductionClass classNamed(List<ProductionClass> classes, String fqn) {
        return classes.stream().filter(c -> c.fqn().equals(fqn)).findFirst()
                .orElseThrow(() -> new AssertionError("Not scanned: " + fqn));
    }

    private ProductionMethod methodNamed(ProductionClass productionClass, String name) {
        return productionClass.methods().stream()
                .filter(m -> m.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No such method: " + name));
    }

    private List<ProductionClass> scanFixtureModule() throws URISyntaxException {
        Path mainSourceRoot = fixtureModuleMainSourceRoot();
        TypeSolver typeSolver = ProductionTypeSolvers.forModule(mainSourceRoot, List.of());
        return new ProductionClassScanner(typeSolver, mainSourceRoot).scan();
    }

    private Path fixtureModuleMainSourceRoot() throws URISyntaxException {
        Path fixtureModule = Path.of(getClass().getClassLoader().getResource("fixture-module").toURI());
        return fixtureModule.resolve("src/main/java");
    }
}
