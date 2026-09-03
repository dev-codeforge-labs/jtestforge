package com.devmanchego.jtestforge.analysis;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.body.MethodDeclaration;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CyclomaticComplexityCalculatorTest {

    @Test
    void aMethodWithNoBranchingHasComplexityOne() {
        assertThat(complexityOf("""
                void m() {
                    int x = 1;
                }
                """)).isEqualTo(1);
    }

    @Test
    void oneIfStatementAddsOne() {
        assertThat(complexityOf("""
                void m(int x) {
                    if (x > 0) {
                        doSomething();
                    }
                }
                """)).isEqualTo(2);
    }

    @Test
    void ifElseIfChainCountsEachIf() {
        assertThat(complexityOf("""
                void m(int x) {
                    if (x > 0) {
                        a();
                    } else if (x < 0) {
                        b();
                    } else {
                        c();
                    }
                }
                """)).isEqualTo(3);
    }

    @Test
    void loopsAndCatchClausesEachAddOne() {
        assertThat(complexityOf("""
                void m(java.util.List<Integer> xs) {
                    for (int x : xs) {
                        try {
                            process(x);
                        } catch (RuntimeException e) {
                            handle(e);
                        }
                    }
                    int i = 0;
                    while (i < 10) {
                        i++;
                    }
                }
                """)).isEqualTo(4);
    }

    @Test
    void aTernaryExpressionAddsOne() {
        assertThat(complexityOf("""
                int m(int x) {
                    return x > 0 ? 1 : -1;
                }
                """)).isEqualTo(2);
    }

    @Test
    void shortCircuitBooleanOperatorsEachAddOne() {
        assertThat(complexityOf("""
                boolean m(boolean a, boolean b, boolean c) {
                    return a && b || c;
                }
                """)).isEqualTo(3);
    }

    @Test
    void nonDefaultSwitchLabelsEachAddOneButDefaultDoesNot() {
        assertThat(complexityOf("""
                void m(int x) {
                    switch (x) {
                        case 1 -> a();
                        case 2 -> b();
                        default -> c();
                    }
                }
                """)).isEqualTo(3);
    }

    @Test
    void anAbstractMethodWithNoBodyHasComplexityOne() {
        MethodDeclaration method = StaticJavaParser.parseBodyDeclaration("abstract void m();")
                .asMethodDeclaration();

        assertThat(CyclomaticComplexityCalculator.calculate(method)).isEqualTo(1);
    }

    private int complexityOf(String methodSource) {
        MethodDeclaration method = StaticJavaParser.parseBodyDeclaration(methodSource).asMethodDeclaration();
        return CyclomaticComplexityCalculator.calculate(method);
    }
}
