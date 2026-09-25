package com.devmanchego.jtestforge.model;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * One class's coverage, joining its {@code <class>} element's methods with its
 * {@code <sourcefile>} element's per-line data — jtestforge-specification.md §9.2, §9.4.
 *
 * @param fqn     fully-qualified class name, dot-separated including nested classes
 *                (e.g. {@code com.acme.Calculator.Formatter}) - converted from JaCoCo's
 *                binary {@code com/acme/Calculator$Formatter} form so it matches the AST
 *                scanner's {@code ProductionClass.fqn()}
 * @param methods every {@code <method>} JaCoCo reported for this class
 * @param lines   per-line instruction/branch counts, keyed by 1-based line number, from
 *                this class's {@code <sourcefile>}
 */
public record ClassCoverage(
        String fqn,
        int instructionsMissed,
        int instructionsCovered,
        int linesMissed,
        int linesCovered,
        int branchesMissed,
        int branchesCovered,
        List<MethodCoverage> methods,
        Map<Integer, LineStatus> lines) {

    public ClassCoverage {
        Objects.requireNonNull(fqn, "fqn");
        methods = methods == null ? List.of() : List.copyOf(methods);
        lines = lines == null ? Map.of() : Map.copyOf(lines);
    }

    /** Every line number JaCoCo instrumented and observed executing at least once. */
    public Set<Integer> coveredLineNumbers() {
        return lines.entrySet().stream()
                .filter(entry -> entry.getValue().isCovered())
                .map(Map.Entry::getKey)
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * Instrumented, never-executed line numbers within {@code [fromLineInclusive,
     * toLineInclusive]} - the range normally coming from a {@code ProductionMethod}'s own
     * {@code startLine()}/{@code endLine()}, feeding {@code {{UNCOVERED_LINES}}} (§6.1).
     */
    public List<Integer> uncoveredLineNumbers(int fromLineInclusive, int toLineInclusive) {
        return lines.entrySet().stream()
                .filter(entry -> entry.getKey() >= fromLineInclusive && entry.getKey() <= toLineInclusive)
                .filter(entry -> !entry.getValue().isCovered())
                .map(Map.Entry::getKey)
                .sorted()
                .toList();
    }

    /**
     * Line numbers within {@code [fromLineInclusive, toLineInclusive]} that executed but
     * took only one of two-or-more branch outcomes - a method can legitimately have zero
     * {@link #uncoveredLineNumbers} yet still be selected as a work unit
     * ({@code WorkUnitDiscovery.hasUncoveredLinesOrBranches}) for exactly these lines, and
     * without this, the model is told nothing reached and nothing else, which cannot be
     * acted on. Feeds {@code {{UNCOVERED_BRANCHES}}} (§6.1).
     */
    public List<Integer> partiallyCoveredBranchLineNumbers(int fromLineInclusive, int toLineInclusive) {
        return lines.entrySet().stream()
                .filter(entry -> entry.getKey() >= fromLineInclusive && entry.getKey() <= toLineInclusive)
                .filter(entry -> entry.getValue().isPartiallyCoveredBranch())
                .map(Map.Entry::getKey)
                .sorted()
                .toList();
    }
}
