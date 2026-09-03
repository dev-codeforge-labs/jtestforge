package com.devmanchego.jtestforge.spring;

import com.devmanchego.jtestforge.analysis.ProductionClassScanner;
import com.devmanchego.jtestforge.analysis.ProductionTypeSolvers;
import com.devmanchego.jtestforge.model.ProductionClass;
import com.devmanchego.jtestforge.model.SemanticGap;
import com.devmanchego.jtestforge.model.SemanticGapKind;
import com.devmanchego.jtestforge.model.Tier;
import com.github.javaparser.resolution.TypeSolver;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs against the fixture module's real {@code OrderController} - the class that exists
 * to prove gate 2 is needed: every line of every handler body is reachable from a plain
 * unit test, so it sits at 100% line coverage with its whole framework contract
 * unverified.
 */
class FrameworkSemanticGapScannerTest {

    private static List<ProductionClass> fixtureClasses;

    private final FrameworkSemanticGapScanner scanner = new FrameworkSemanticGapScanner();

    @BeforeAll
    static void scanFixtureModule() throws URISyntaxException {
        Path fixtureModule = Path.of(FrameworkSemanticGapScannerTest.class.getClassLoader()
                .getResource("fixture-module").toURI());
        Path mainSourceRoot = fixtureModule.resolve("src/main/java");
        TypeSolver typeSolver = ProductionTypeSolvers.forModule(mainSourceRoot, List.of());
        fixtureClasses = new ProductionClassScanner(typeSolver, mainSourceRoot).scan();
    }

    @Test
    void aControllerAtFullLineCoverageStillRaisesMappingValidationSecurityAndErrorGaps() {
        List<SemanticGap> gaps = scanner.scan(fixtureClass("com.acme.web.OrderController"));

        assertThat(gaps).extracting(SemanticGap::kind).contains(
                SemanticGapKind.REQUEST_MAPPING,
                SemanticGapKind.BEAN_VALIDATION,
                SemanticGapKind.METHOD_SECURITY,
                SemanticGapKind.EXCEPTION_TRANSLATION);
    }

    @Test
    void aMappingGapCarriesTheHttpMethodAndPathItWasRaisedFor() {
        List<SemanticGap> gaps = scanner.scan(fixtureClass("com.acme.web.OrderController"));

        SemanticGap findByIdMapping = gaps.stream()
                .filter(gap -> gap.kind() == SemanticGapKind.REQUEST_MAPPING)
                .filter(gap -> "findById".equals(gap.methodName()))
                .findFirst().orElseThrow();

        assertThat(findByIdMapping.httpMethod()).isEqualTo("GET");
        assertThat(findByIdMapping.path()).isEqualTo("/api/orders/{id}");
        assertThat(findByIdMapping.closingTier()).isEqualTo(Tier.WEB_SLICE);
    }

    @Test
    void gapDescriptionsAreBehaviouralStatementsNotAnnotationDumps() {
        // §7.5: the model is told what behaviour is unverified, never "add an assertion
        // for the @PreAuthorize annotation on line 31" - the latter produces tests
        // coupled to the implementation rather than to the contract.
        SemanticGap securityGap = scanner.scan(fixtureClass("com.acme.web.OrderController")).stream()
                .filter(gap -> gap.kind() == SemanticGapKind.METHOD_SECURITY)
                .findFirst().orElseThrow();

        assertThat(securityGap.description()).doesNotContain("@PreAuthorize");
        assertThat(securityGap.description()).doesNotContain("annotation");
        assertThat(securityGap.description()).containsIgnoringCase("unauthorised");
    }

    @Test
    void aPlainServiceRaisesNoWebGapsAtAll() {
        List<SemanticGap> gaps = scanner.scan(fixtureClass("com.acme.BillingService"));

        assertThat(gaps).extracting(SemanticGap::kind)
                .doesNotContain(SemanticGapKind.REQUEST_MAPPING, SemanticGapKind.BEAN_VALIDATION,
                        SemanticGapKind.METHOD_SECURITY);
    }

    @Test
    void aTransactionalMethodRaisesARollbackGapOnlyWhenARollbackRuleIsDeclared() {
        List<SemanticGap> gaps = scanner.scan(fixtureClass("com.acme.BillingService"));

        List<String> rollbackGapMethods = gaps.stream()
                .filter(gap -> gap.kind() == SemanticGapKind.TRANSACTION_ROLLBACK)
                .map(SemanticGap::methodName)
                .toList();

        // settle declares rollbackFor; archive is plain @Transactional, where there is no
        // declared behaviour to verify beyond Spring's own default.
        assertThat(rollbackGapMethods).containsExactly("settle");
    }

    @Test
    void aPlainDomainClassWithNoSpringAnnotationsRaisesNothing() {
        assertThat(scanner.scan(fixtureClass("com.acme.PaymentService"))).isEmpty();
    }

    @Test
    void aConfigurationPropertiesTypeRaisesABindingGap() {
        List<SemanticGap> gaps = scanner.scan(fixtureClass("com.acme.config.BillingProperties"));

        assertThat(gaps).extracting(SemanticGap::kind)
                .containsExactly(SemanticGapKind.CONFIGURATION_PROPERTIES_BINDING);
        assertThat(gaps.get(0).closingTier()).isEqualTo(Tier.JSON_SLICE);
    }

    @Test
    void gapIdsAreStableAcrossLineMovement() {
        SemanticGap gap = scanner.scan(fixtureClass("com.acme.web.OrderController")).stream()
                .filter(g -> g.kind() == SemanticGapKind.REQUEST_MAPPING)
                .filter(g -> "findById".equals(g.methodName()))
                .findFirst().orElseThrow();

        // Same gap, recorded ten lines further down after unrelated edits above it.
        SemanticGap moved = new SemanticGap(gap.kind(), gap.className(), gap.methodName(),
                gap.description(), gap.httpMethod(), gap.path(), gap.sourceLine() + 10);

        assertThat(moved.id()).isEqualTo(gap.id());
    }

    private ProductionClass fixtureClass(String fqn) {
        return fixtureClasses.stream()
                .filter(c -> c.fqn().equals(fqn))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Fixture class not scanned: " + fqn));
    }
}
