package com.acme.calc;

/**
 * Fixture for a real, recorded jacoco.xml: deliberately exercises the cases phase 8's
 * join must get right without help - two overloads (only one of which is called), a
 * branchy method that is only partially exercised, a method never called at all, a
 * varargs method, and a method on an inner (nested) class.
 */
public class Calculator {

    public int add(int a, int b) {
        return a + b;
    }

    public double add(double a, double b) {
        return a + b;
    }

    public int classify(int value) {
        if (value > 100) {
            return 2;
        } else if (value > 10) {
            return 1;
        }
        return 0;
    }

    public int neverCalled(int x) {
        return x * 2;
    }

    public int sum(int... values) {
        int total = 0;
        for (int value : values) {
            total += value;
        }
        return total;
    }

    public static class Formatter {
        public String format(int value) {
            return "value=" + value;
        }
    }
}
