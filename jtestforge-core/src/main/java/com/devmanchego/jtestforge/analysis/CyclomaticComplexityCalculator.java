package com.devmanchego.jtestforge.analysis;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.DoStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.SwitchEntry;
import com.github.javaparser.ast.stmt.WhileStmt;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;

/**
 * Approximate cyclomatic complexity of a method body — jtestforge-specification.md §7.1,
 * used by {@code selection.minComplexity} to skip trivial methods not worth a generated
 * test.
 *
 * <p>"Approximate" deliberately: this counts the standard decision points (branches,
 * loops, catch clauses, switch labels, the ternary operator, short-circuit
 * {@code &&}/{@code ||}) and adds one, McCabe's usual formula. It does not attempt
 * full control-flow-graph precision (e.g. it does not special-case fallthrough switch
 * labels or unreachable code) - that precision would not change which methods clear a
 * small integer threshold like the default {@code minComplexity: 2}.
 */
final class CyclomaticComplexityCalculator {

    private CyclomaticComplexityCalculator() {
    }

    static int calculate(MethodDeclaration method) {
        if (method.getBody().isEmpty()) {
            return 1;
        }
        DecisionPointCounter counter = new DecisionPointCounter();
        method.getBody().get().accept(counter, null);
        return 1 + counter.count;
    }

    private static final class DecisionPointCounter extends VoidVisitorAdapter<Void> {
        private int count;

        @Override
        public void visit(IfStmt node, Void arg) {
            count++;
            super.visit(node, arg);
        }

        @Override
        public void visit(ForStmt node, Void arg) {
            count++;
            super.visit(node, arg);
        }

        @Override
        public void visit(ForEachStmt node, Void arg) {
            count++;
            super.visit(node, arg);
        }

        @Override
        public void visit(WhileStmt node, Void arg) {
            count++;
            super.visit(node, arg);
        }

        @Override
        public void visit(DoStmt node, Void arg) {
            count++;
            super.visit(node, arg);
        }

        @Override
        public void visit(CatchClause node, Void arg) {
            count++;
            super.visit(node, arg);
        }

        @Override
        public void visit(ConditionalExpr node, Void arg) {
            count++;
            super.visit(node, arg);
        }

        @Override
        public void visit(SwitchEntry node, Void arg) {
            // A default label (empty labels list) is not itself a decision point - the
            // decision was already counted by every non-default label it falls after.
            if (!node.getLabels().isEmpty()) {
                count++;
            }
            super.visit(node, arg);
        }

        @Override
        public void visit(BinaryExpr node, Void arg) {
            if (node.getOperator() == BinaryExpr.Operator.AND || node.getOperator() == BinaryExpr.Operator.OR) {
                count++;
            }
            super.visit(node, arg);
        }
    }
}
