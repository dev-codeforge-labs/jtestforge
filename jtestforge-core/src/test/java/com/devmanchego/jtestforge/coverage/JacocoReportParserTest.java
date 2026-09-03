package com.devmanchego.jtestforge.coverage;

import com.devmanchego.jtestforge.model.ClassCoverage;
import com.devmanchego.jtestforge.model.MethodCoverage;
import org.junit.jupiter.api.Test;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Against a real, recorded {@code jacoco.xml} captured from an actual
 * {@code mvn test jacoco:report} run over jacoco-fixture-module/src/main/java/com/acme/calc/Calculator.java
 * (see that module's pom.xml and CalculatorTest.java for what was and wasn't exercised).
 */
class JacocoReportParserTest {

    private final JacocoReportParser parser = new JacocoReportParser();

    @Test
    void parsesBothTheOuterAndTheNestedClassWithDotSeparatedNames() throws URISyntaxException {
        List<ClassCoverage> classes = parser.parse(fixtureFile());

        assertThat(classes).extracting(ClassCoverage::fqn).containsExactlyInAnyOrder(
                "com.acme.calc.Calculator", "com.acme.calc.Calculator.Formatter");
    }

    @Test
    void constructorsAppearAsInitInTheRawMethodListButAreNotFilteredHereYet() throws URISyntaxException {
        // Filtering <init>/<clinit> out is CoverageMethodJoiner's job (it has nothing to
        // match them against); the parser itself reports exactly what JaCoCo said.
        ClassCoverage calculator = classNamed(parser.parse(fixtureFile()), "com.acme.calc.Calculator");

        assertThat(calculator.methods()).extracting(MethodCoverage::name).contains("<init>");
    }

    @Test
    void distinguishesTheTwoOverloadsByDescriptor() throws URISyntaxException {
        ClassCoverage calculator = classNamed(parser.parse(fixtureFile()), "com.acme.calc.Calculator");

        List<MethodCoverage> addMethods = calculator.methods().stream()
                .filter(m -> m.name().equals("add")).toList();

        assertThat(addMethods).extracting(MethodCoverage::jvmDescriptor)
                .containsExactlyInAnyOrder("(II)I", "(DD)D");
    }

    @Test
    void theCoveredOverloadHasCoveredLinesAndTheUncalledOneHasZero() throws URISyntaxException {
        ClassCoverage calculator = classNamed(parser.parse(fixtureFile()), "com.acme.calc.Calculator");

        MethodCoverage intAdd = methodWithDescriptor(calculator, "add", "(II)I");
        MethodCoverage doubleAdd = methodWithDescriptor(calculator, "add", "(DD)D");

        assertThat(intAdd.linesCovered()).isEqualTo(1);
        assertThat(intAdd.linesMissed()).isZero();
        assertThat(doubleAdd.linesCovered()).isZero();
        assertThat(doubleAdd.linesMissed()).isEqualTo(1);
    }

    @Test
    void aMethodWithNoBranchesHasZeroBranchCountersRatherThanBeingUnreadable() throws URISyntaxException {
        // add(int,int) has no <counter type="BRANCH"> element at all in the real report
        // (JaCoCo omits zero-count counter types entirely) - this is the exact case the
        // parser must default rather than mishandle.
        ClassCoverage calculator = classNamed(parser.parse(fixtureFile()), "com.acme.calc.Calculator");
        MethodCoverage intAdd = methodWithDescriptor(calculator, "add", "(II)I");

        assertThat(intAdd.branchesMissed()).isZero();
        assertThat(intAdd.branchesCovered()).isZero();
    }

    @Test
    void aPartiallyExercisedBranchyMethodHasBothMissedAndCoveredBranches() throws URISyntaxException {
        ClassCoverage calculator = classNamed(parser.parse(fixtureFile()), "com.acme.calc.Calculator");
        MethodCoverage classify = methodWithDescriptor(calculator, "classify", "(I)I");

        assertThat(classify.branchesMissed()).isEqualTo(2);
        assertThat(classify.branchesCovered()).isEqualTo(2);
        assertThat(classify.linesMissed()).isEqualTo(2);
        assertThat(classify.linesCovered()).isEqualTo(3);
    }

    @Test
    void aNeverCalledMethodIsFullyMissed() throws URISyntaxException {
        ClassCoverage calculator = classNamed(parser.parse(fixtureFile()), "com.acme.calc.Calculator");
        MethodCoverage neverCalled = methodWithDescriptor(calculator, "neverCalled", "(I)I");

        assertThat(neverCalled.linesCovered()).isZero();
        assertThat(neverCalled.instructionsCovered()).isZero();
    }

    @Test
    void theNestedClasssMethodIsReportedUnderTheNestedClassNotTheOuterOne() throws URISyntaxException {
        List<ClassCoverage> classes = parser.parse(fixtureFile());
        ClassCoverage formatter = classNamed(classes, "com.acme.calc.Calculator.Formatter");
        ClassCoverage outer = classNamed(classes, "com.acme.calc.Calculator");

        assertThat(formatter.methods()).extracting(MethodCoverage::name).contains("format");
        assertThat(outer.methods()).extracting(MethodCoverage::name).doesNotContain("format");
    }

    @Test
    void theCoveredLineSetComesFromTheSharedSourcefileAndCoversBothClasses() throws URISyntaxException {
        // Calculator and Calculator.Formatter are declared in the same .java file and
        // share one <sourcefile> line table in the report.
        List<ClassCoverage> classes = parser.parse(fixtureFile());
        ClassCoverage outer = classNamed(classes, "com.acme.calc.Calculator");
        ClassCoverage formatter = classNamed(classes, "com.acme.calc.Calculator.Formatter");

        // format() is declared at line 42 (real source line - see Calculator.java) and was
        // exercised by CalculatorTest.
        assertThat(formatter.coveredLineNumbers()).contains(42);
        assertThat(outer.coveredLineNumbers()).contains(12); // add(int,int)'s line
    }

    @Test
    void uncoveredLineNumbersAreScopedToTheRequestedRange() throws URISyntaxException {
        ClassCoverage calculator = classNamed(parser.parse(fixtureFile()), "com.acme.calc.Calculator");

        // classify(int) spans lines 19-26 in source; line 21 (the `> 100` branch body) and
        // line 25 (the fall-through zero) are the ones never reached by classify(50).
        List<Integer> uncovered = calculator.uncoveredLineNumbers(19, 26);

        assertThat(uncovered).contains(21, 25);
        assertThat(uncovered).doesNotContain(20, 22, 23); // these were reached
    }

    @Test
    void classLevelAggregateCountersReflectAllMethodsInTheClass() throws URISyntaxException {
        ClassCoverage calculator = classNamed(parser.parse(fixtureFile()), "com.acme.calc.Calculator");

        // Matches the real <class>-level <counter type="METHOD" missed="2" covered="4"/>
        // in the recorded fixture (add(double,double) and neverCalled are the two missed).
        assertThat(calculator.linesMissed()).isEqualTo(4);
        assertThat(calculator.linesCovered()).isEqualTo(9);
        assertThat(calculator.branchesMissed()).isEqualTo(2);
        assertThat(calculator.branchesCovered()).isEqualTo(4);
    }

    private MethodCoverage methodWithDescriptor(ClassCoverage classCoverage, String name, String descriptor) {
        return classCoverage.methods().stream()
                .filter(m -> m.name().equals(name) && m.jvmDescriptor().equals(descriptor))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No method " + name + descriptor));
    }

    private ClassCoverage classNamed(List<ClassCoverage> classes, String fqn) {
        return classes.stream().filter(c -> c.fqn().equals(fqn)).findFirst()
                .orElseThrow(() -> new AssertionError("Class not found: " + fqn));
    }

    private Path fixtureFile() throws URISyntaxException {
        return Path.of(getClass().getClassLoader().getResource("jacoco-fixture/jacoco.xml").toURI());
    }
}
