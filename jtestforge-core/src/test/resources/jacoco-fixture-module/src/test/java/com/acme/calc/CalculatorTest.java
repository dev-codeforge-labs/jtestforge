package com.acme.calc;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CalculatorTest {

    private final Calculator calculator = new Calculator();

    @Test
    void addsTwoInts() {
        assertEquals(5, calculator.add(2, 3));
    }

    // add(double, double) is deliberately never called - an intentionally uncovered
    // overload of a covered method.

    @Test
    void classifiesAMidRangeValue() {
        // Only the value > 10 branch is exercised; the value > 100 branch and the
        // fall-through-to-zero branch are both deliberately left uncovered.
        assertEquals(1, calculator.classify(50));
    }

    @Test
    void sumsVarargs() {
        assertEquals(6, calculator.sum(1, 2, 3));
    }

    @Test
    void formatsThroughTheNestedClass() {
        assertEquals("value=7", new Calculator.Formatter().format(7));
    }

    // neverCalled is deliberately never invoked by any test.
}
