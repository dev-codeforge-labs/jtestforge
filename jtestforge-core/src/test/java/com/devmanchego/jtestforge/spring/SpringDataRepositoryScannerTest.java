package com.devmanchego.jtestforge.spring;

import com.devmanchego.jtestforge.analysis.ProductionClassScanner;
import com.devmanchego.jtestforge.analysis.ProductionTypeSolvers;
import com.devmanchego.jtestforge.model.ProductionClass;
import com.github.javaparser.JavaParser;
import org.junit.jupiter.api.Test;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SpringDataRepositoryScannerTest {

    @Test
    void findsRepositoryInterfacesThatTheProductionClassScannerDeliberatelySkips() throws URISyntaxException {
        List<ProductionClass> repositories = scanFixtureModule();

        assertThat(repositories).extracting(ProductionClass::fqn)
                .containsExactly("com.acme.repo.OrderRepository");
    }

    @Test
    void recordsTheDerivedAndDeclaredQueryMethods() throws URISyntaxException {
        ProductionClass repository = scanFixtureModule().get(0);

        assertThat(repository.methods()).extracting(m -> m.name())
                .containsExactlyInAnyOrder("findByReferenceAndStatus", "findExpensive");
    }

    @Test
    void recordsTheSpringDataSupertypeSoTheTierClassifierCanRecogniseIt() throws URISyntaxException {
        ProductionClass repository = scanFixtureModule().get(0);

        assertThat(repository.supertypes()).contains("JpaRepository");
    }

    @Test
    void queryMethodsAreNeverFilteredOutByAComplexityThreshold() throws URISyntaxException {
        // A derived query has no body, so any real complexity number would be 1 and
        // selection.minComplexity would quietly drop exactly the methods that most need
        // a DataJpaTest.
        ProductionClass repository = scanFixtureModule().get(0);

        assertThat(repository.methods()).allSatisfy(method ->
                assertThat(method.cyclomaticComplexity()).isEqualTo(Integer.MAX_VALUE));
    }

    @Test
    void aModuleWithNoRepositoriesYieldsNothingRatherThanFailing(@org.junit.jupiter.api.io.TempDir Path emptyDir) {
        SpringDataRepositoryScanner scanner = new SpringDataRepositoryScanner(
                new JavaParser(ProductionClassScanner.parserConfiguration(
                        ProductionTypeSolvers.forModule(emptyDir, List.of()))),
                emptyDir);

        assertThat(scanner.scan()).isEmpty();
    }

    private List<ProductionClass> scanFixtureModule() throws URISyntaxException {
        Path fixtureModule = Path.of(getClass().getClassLoader().getResource("fixture-module").toURI());
        Path mainSourceRoot = fixtureModule.resolve("src/main/java");
        JavaParser javaParser = new JavaParser(ProductionClassScanner.parserConfiguration(
                ProductionTypeSolvers.forModule(mainSourceRoot, List.of())));
        return new SpringDataRepositoryScanner(javaParser, mainSourceRoot).scan();
    }
}
