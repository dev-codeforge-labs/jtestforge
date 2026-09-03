package com.devmanchego.jtestforge.coverage;

import com.devmanchego.jtestforge.analysis.ProductionClassScanner;
import com.devmanchego.jtestforge.analysis.ProductionTypeSolvers;
import com.devmanchego.jtestforge.model.ClassCoverage;
import com.devmanchego.jtestforge.model.MethodCoverage;
import com.devmanchego.jtestforge.model.ProductionClass;
import com.devmanchego.jtestforge.model.ProductionMethod;
import com.github.javaparser.resolution.TypeSolver;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The real join: a real AST scan of {@code Calculator.java} (phase 3's
 * {@code ProductionClassScanner}) matched against the real, recorded {@code jacoco.xml}
 * from an actual {@code mvn test jacoco:report} run over the same file. No synthetic
 * fixtures on either side of the join - this is where an AST/bytecode signature mismatch
 * would actually show up.
 */
class CoverageMethodJoinerTest {

    private static List<ProductionClass> scannedClasses;
    private static List<ClassCoverage> coverageClasses;

    private final CoverageMethodJoiner joiner = new CoverageMethodJoiner();

    @BeforeAll
    static void scanAndParse() throws URISyntaxException {
        Path jacocoModule = Path.of(CoverageMethodJoinerTest.class.getClassLoader()
                .getResource("jacoco-fixture-module").toURI());
        Path mainSourceRoot = jacocoModule.resolve("src/main/java");
        TypeSolver typeSolver = ProductionTypeSolvers.forModule(mainSourceRoot, List.of());
        scannedClasses = new ProductionClassScanner(typeSolver, mainSourceRoot).scan();

        Path jacocoXml = Path.of(CoverageMethodJoinerTest.class.getClassLoader()
                .getResource("jacoco-fixture/jacoco.xml").toURI());
        coverageClasses = new JacocoReportParser().parse(jacocoXml);
    }

    @Test
    void everyRealMethodOnCalculatorHasAMatchingCoverageEntry() {
        ProductionClass calculator = classNamed("com.acme.calc.Calculator");
        ClassCoverage coverage = coverageNamed("com.acme.calc.Calculator");

        Map<ProductionMethod, MethodCoverage> joined = joiner.joinAll(calculator, coverage);

        assertThat(joined).hasSameSizeAs(calculator.methods());
    }

    @Test
    void theTwoOverloadsOfAddAreNotConflated() {
        ProductionClass calculator = classNamed("com.acme.calc.Calculator");
        ClassCoverage coverage = coverageNamed("com.acme.calc.Calculator");

        ProductionMethod intAdd = methodNamed(calculator, "add", 2);
        ProductionMethod doubleAdd = methodNamed(calculator, "add", m -> m.parameterTypes().contains("double"));

        MethodCoverage intCoverage = joiner.find(intAdd, coverage).orElseThrow();
        MethodCoverage doubleCoverage = joiner.find(doubleAdd, coverage).orElseThrow();

        assertThat(intCoverage.jvmDescriptor()).isEqualTo("(II)I");
        assertThat(doubleCoverage.jvmDescriptor()).isEqualTo("(DD)D");
        // The whole point of the join: the covered overload must never be reported as
        // the uncovered one, or vice versa.
        assertThat(intCoverage.linesCovered()).isEqualTo(1);
        assertThat(doubleCoverage.linesCovered()).isZero();
    }

    @Test
    void theVarargsMethodJoinsCorrectlyAgainstItsArrayDescriptor() {
        // This is the case the AST and the descriptor could plausibly disagree on:
        // sum(int... values) must join to the "([I)I" entry.
        ProductionClass calculator = classNamed("com.acme.calc.Calculator");
        ClassCoverage coverage = coverageNamed("com.acme.calc.Calculator");
        ProductionMethod sum = methodNamed(calculator, "sum", 1);

        Optional<MethodCoverage> matched = joiner.find(sum, coverage);

        assertThat(matched).isPresent();
        assertThat(matched.get().jvmDescriptor()).isEqualTo("([I)I");
        assertThat(matched.get().linesCovered()).isGreaterThan(0);
    }

    @Test
    void theNestedClasssMethodJoinsAgainstTheNestedClassesOwnCoverageEntry() {
        ProductionClass formatter = classNamed("com.acme.calc.Calculator.Formatter");
        ClassCoverage coverage = coverageNamed("com.acme.calc.Calculator.Formatter");
        ProductionMethod format = methodNamed(formatter, "format", 1);

        Optional<MethodCoverage> matched = joiner.find(format, coverage);

        assertThat(matched).isPresent();
        assertThat(matched.get().linesCovered()).isEqualTo(1);
    }

    @Test
    void aNeverCalledMethodStillJoinsAndReportsZeroCoverage() {
        ProductionClass calculator = classNamed("com.acme.calc.Calculator");
        ClassCoverage coverage = coverageNamed("com.acme.calc.Calculator");
        ProductionMethod neverCalled = methodNamed(calculator, "neverCalled", 1);

        Optional<MethodCoverage> matched = joiner.find(neverCalled, coverage);

        assertThat(matched).isPresent();
        assertThat(matched.get().linesCovered()).isZero();
    }

    private ProductionClass classNamed(String fqn) {
        return scannedClasses.stream().filter(c -> c.fqn().equals(fqn)).findFirst()
                .orElseThrow(() -> new AssertionError("Not scanned: " + fqn));
    }

    private ClassCoverage coverageNamed(String fqn) {
        return coverageClasses.stream().filter(c -> c.fqn().equals(fqn)).findFirst()
                .orElseThrow(() -> new AssertionError("No coverage for: " + fqn));
    }

    private ProductionMethod methodNamed(ProductionClass productionClass, String name, int parameterCount) {
        return methodNamed(productionClass, name, m -> m.parameterTypes().size() == parameterCount);
    }

    private ProductionMethod methodNamed(
            ProductionClass productionClass, String name, java.util.function.Predicate<ProductionMethod> extra) {
        return productionClass.methods().stream()
                .filter(m -> m.name().equals(name))
                .filter(extra)
                .findFirst()
                .orElseThrow(() -> new AssertionError("No such method: " + name));
    }
}
