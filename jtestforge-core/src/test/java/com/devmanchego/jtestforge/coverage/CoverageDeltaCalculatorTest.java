package com.devmanchego.jtestforge.coverage;

import com.devmanchego.jtestforge.model.ClassCoverage;
import com.devmanchego.jtestforge.model.CoverageDelta;
import com.devmanchego.jtestforge.model.LineStatus;
import com.devmanchego.jtestforge.model.MethodCoverage;
import com.devmanchego.jtestforge.model.ProductionMethod;
import com.devmanchego.jtestforge.model.Visibility;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CoverageDeltaCalculatorTest {

    private final CoverageDeltaCalculator calculator = new CoverageDeltaCalculator();

    @Test
    void aMethodThatGainedCoveredLinesReportsAPositiveDelta() {
        ProductionMethod method = methodAt(10, 15);
        ClassCoverage before = classCoverage(
                methodCoverage("applyFee", "()V", 10, 2, 0, 0),
                lines(Map.of(11, uncovered(), 12, uncovered())));
        ClassCoverage after = classCoverage(
                methodCoverage("applyFee", "()V", 10, 5, 0, 0),
                lines(Map.of(11, covered(), 12, uncovered())));

        CoverageDelta delta = calculator.delta(method, before, after);

        assertThat(delta.linesCoveredDelta()).isEqualTo(3);
        assertThat(delta.hasImproved()).isTrue();
    }

    @Test
    void aMethodWithNoChangeReportsAZeroDeltaAndDoesNotImprove() {
        ProductionMethod method = methodAt(10, 15);
        ClassCoverage before = classCoverage(
                methodCoverage("applyFee", "()V", 10, 5, 0, 0), Map.of());
        ClassCoverage after = classCoverage(
                methodCoverage("applyFee", "()V", 10, 5, 0, 0), Map.of());

        CoverageDelta delta = calculator.delta(method, before, after);

        assertThat(delta.linesCoveredDelta()).isZero();
        assertThat(delta.branchesCoveredDelta()).isZero();
        assertThat(delta.hasImproved()).isFalse();
    }

    @Test
    void newlyCoveredLinesListsExactlyTheLinesThatFlippedFromMissedToCovered() {
        ProductionMethod method = methodAt(10, 14);
        ClassCoverage before = classCoverage(
                methodCoverage("applyFee", "()V", 10, 1, 0, 0),
                lines(Map.of(11, uncovered(), 12, covered(), 13, uncovered())));
        ClassCoverage after = classCoverage(
                methodCoverage("applyFee", "()V", 10, 3, 0, 0),
                lines(Map.of(11, covered(), 12, covered(), 13, covered())));

        CoverageDelta delta = calculator.delta(method, before, after);

        assertThat(delta.newlyCoveredLines()).containsExactly(11, 13);
    }

    @Test
    void branchCoverageDeltaIsTrackedSeparatelyFromLineCoverage() {
        // Must carry one "int" parameter to join against the "(I)I" descriptor below -
        // CoverageMethodJoiner matches on name AND parameter types.
        ProductionMethod method = new ProductionMethod("classify", "int",
                List.of(new com.devmanchego.jtestforge.model.ProductionParameter("value", "int", List.of())),
                Visibility.PUBLIC, false, List.of(), Map.of(), List.of(), 10, 20, 2);
        ClassCoverage before = classCoverage(
                methodCoverage("classify", "(I)I", 10, 5, 0, 1), Map.of());
        ClassCoverage after = classCoverage(
                methodCoverage("classify", "(I)I", 10, 5, 0, 3), Map.of());

        CoverageDelta delta = calculator.delta(method, before, after);

        assertThat(delta.linesCoveredDelta()).isZero();
        assertThat(delta.branchesCoveredDelta()).isEqualTo(2);
        assertThat(delta.hasImproved()).isTrue();
    }

    @Test
    void aMethodAbsentFromTheBeforeReportIsTreatedAsStartingFromZero() {
        // e.g. the class did not exist yet, or JaCoCo simply never instrumented it in
        // that earlier run - the delta must still be meaningful, not throw.
        ProductionMethod method = methodAt(10, 12);
        ClassCoverage before = classCoverage(List.of(), Map.of());
        ClassCoverage after = classCoverage(
                methodCoverage("applyFee", "()V", 10, 2, 0, 0), Map.of());

        CoverageDelta delta = calculator.delta(method, before, after);

        assertThat(delta.linesCoveredDelta()).isEqualTo(2);
    }

    @Test
    void deltasAreNeverNegativeEvenIfCoverageAppearsToRegress() {
        ProductionMethod method = methodAt(10, 15);
        ClassCoverage before = classCoverage(
                methodCoverage("applyFee", "()V", 10, 5, 0, 2), Map.of());
        ClassCoverage after = classCoverage(
                methodCoverage("applyFee", "()V", 10, 3, 0, 1), Map.of());

        CoverageDelta delta = calculator.delta(method, before, after);

        assertThat(delta.linesCoveredDelta()).isZero();
        assertThat(delta.branchesCoveredDelta()).isZero();
    }

    // --- fixtures -----------------------------------------------------------------

    private ProductionMethod methodAt(int startLine, int endLine) {
        return methodNamed("applyFee", startLine, endLine);
    }

    private ProductionMethod methodNamed(String name, int startLine, int endLine) {
        return new ProductionMethod(name, "java.math.BigDecimal", List.of(), Visibility.PUBLIC, false,
                List.of(), Map.of(), List.of(), startLine, endLine, 2);
    }

    private MethodCoverage methodCoverage(String name, String descriptor, int startLine,
                                          int linesCovered, int branchesMissed, int branchesCovered) {
        return new MethodCoverage(name, descriptor, startLine, 0, 0, 0, linesCovered,
                branchesMissed, branchesCovered);
    }

    private ClassCoverage classCoverage(MethodCoverage method, Map<Integer, LineStatus> lines) {
        return classCoverage(List.of(method), lines);
    }

    private ClassCoverage classCoverage(List<MethodCoverage> methods, Map<Integer, LineStatus> lines) {
        return new ClassCoverage("com.acme.Subject", 0, 0, 0, 0, 0, 0, methods, lines);
    }

    private Map<Integer, LineStatus> lines(Map<Integer, LineStatus> lines) {
        return lines;
    }

    private LineStatus covered() {
        return new LineStatus(0, 1, 0, 0);
    }

    private LineStatus uncovered() {
        return new LineStatus(1, 0, 0, 0);
    }
}
