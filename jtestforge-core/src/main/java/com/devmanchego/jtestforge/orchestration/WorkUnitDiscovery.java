package com.devmanchego.jtestforge.orchestration;

import com.devmanchego.jtestforge.analysis.SelectionFilter;
import com.devmanchego.jtestforge.analysis.TestClassLocator;
import com.devmanchego.jtestforge.config.JTestForgeConfig;
import com.devmanchego.jtestforge.config.SelectionOrder;
import com.devmanchego.jtestforge.coverage.CoverageMethodJoiner;
import com.devmanchego.jtestforge.model.ClassCoverage;
import com.devmanchego.jtestforge.model.MethodCoverage;
import com.devmanchego.jtestforge.model.ProductionClass;
import com.devmanchego.jtestforge.model.ProductionMethod;
import com.devmanchego.jtestforge.model.SemanticGap;
import com.devmanchego.jtestforge.model.Tier;
import com.devmanchego.jtestforge.model.UnitStatus;
import com.devmanchego.jtestforge.model.Visibility;
import com.devmanchego.jtestforge.model.WorkUnit;
import com.devmanchego.jtestforge.model.WorkUnitId;
import com.devmanchego.jtestforge.spring.FrameworkSemanticGapScanner;
import com.devmanchego.jtestforge.spring.GapSuppressionDetector;
import com.devmanchego.jtestforge.spring.TierClassifier;
import com.devmanchego.jtestforge.util.FileHasher;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Work unit selection — jtestforge-specification.md §9 step 3.
 *
 * <p>Crosses the AST scan with the coverage snapshot and the framework-semantic gap scan:
 * a method becomes a unit when it passes the {@code selection} filters and either has no
 * test class at all, has uncovered lines/branches, or carries an unverified framework
 * semantic.
 *
 * <p>Two units for one method are not a mistake. A {@code @RestController} handler
 * legitimately produces a {@code PLAIN_UNIT} unit for the branching inside its body
 * <em>and</em> a Spring-tier unit for the mapping, binding and validation contract around
 * it (§7.4's escalation rule) - which is exactly why {@link WorkUnitId} carries the tier
 * in its identity. The Spring-tier unit is raised only for methods that actually have a
 * gap, so a fully-verified handler produces just the plain one.
 *
 * <p>Ordering across tiers is applied by {@link TierScheduler} at execution time (cheapest
 * tier first, class-grouped); what this class controls is the order <em>within</em> a
 * tier, from {@code selection.order}.
 */
public final class WorkUnitDiscovery {

    private final JTestForgeConfig config;
    private final TestClassLocator testClassLocator;

    private final SelectionFilter selectionFilter;
    private final TierClassifier tierClassifier = new TierClassifier();
    private final FrameworkSemanticGapScanner gapScanner = new FrameworkSemanticGapScanner();
    private final GapSuppressionDetector gapSuppressionDetector = new GapSuppressionDetector();
    private final CoverageMethodJoiner coverageJoiner = new CoverageMethodJoiner();

    public WorkUnitDiscovery(JTestForgeConfig config, TestClassLocator testClassLocator) {
        this.config = Objects.requireNonNull(config, "config");
        this.testClassLocator = Objects.requireNonNull(testClassLocator, "testClassLocator");
        this.selectionFilter = new SelectionFilter(config.selection());
    }

    public List<DiscoveredUnit> discover(DiscoveryInputs inputs) {
        List<DiscoveredUnit> units = new ArrayList<>();
        for (ProductionClass productionClass : inputs.productionClasses()) {
            if (!selectionFilter.isClassIncluded(productionClass)) {
                continue;
            }
            units.addAll(unitsFor(productionClass, inputs));
        }
        return orderWithinTier(units);
    }

    private List<DiscoveredUnit> unitsFor(ProductionClass productionClass, DiscoveryInputs inputs) {
        ClassCoverage coverage = inputs.coverageByClassFqn().get(productionClass.fqn());
        Tier springTier = gapClosingTierOf(productionClass);
        List<SemanticGap> openGaps = openGapsOf(productionClass, springTier);

        List<DiscoveredUnit> units = new ArrayList<>();
        for (ProductionMethod method : productionClass.methods()) {
            if (method.visibility() == Visibility.PRIVATE && !config.selection().includePrivateMethods()) {
                // A private method has no framework contract and no slice test can reach
                // it, so this exclusion governs both kinds of unit.
                continue;
            }
            if (selectionFilter.isMethodIncluded(method)) {
                plainUnitFor(productionClass, method, coverage).ifPresent(units::add);
            }
            springUnitFor(productionClass, method, springTier, openGaps, coverage, inputs).ifPresent(units::add);
        }
        return units;
    }

    /**
     * The tier able to close this class's framework-semantic gaps.
     *
     * <p>Deliberately <b>not</b> {@code TierClassifier.primaryTier}. That method answers
     * "which single tier does this class get", and under {@code preferLowestTier} (the
     * default) it answers {@code PLAIN_UNIT} for everything - correct for its own callers
     * ({@code scan}'s one-line-per-class breakdown), and fatal here: it would mean no
     * Spring unit is ever raised, which is precisely the behaviour gate 2 exists to
     * provide. §7.4's escalation rule says the preference stops the <em>class</em> being
     * lifted wholesale, and that a {@code @RestController} "typically produces both kinds
     * of unit" - plain ones for its handler bodies, Spring ones for the contract around
     * them. The natural tier of the stereotype is what can close a gap, whatever the
     * preference says about the class as a whole.
     */
    private Tier gapClosingTierOf(ProductionClass productionClass) {
        return tierClassifier.naturalTierOf(tierClassifier.classify(productionClass));
    }

    /**
     * A {@code PLAIN_UNIT} unit exists when the method body is not fully exercised, or the
     * class has no plain test class at all. A class whose natural tier is above
     * {@code PLAIN_UNIT} still gets plain units for its method bodies (§7.4: a
     * {@code @RestController} typically produces <em>both</em> kinds of unit).
     */
    private Optional<DiscoveredUnit> plainUnitFor(
            ProductionClass productionClass, ProductionMethod method, ClassCoverage coverage) {
        Optional<Path> existingTestFile = testClassLocator.locate(productionClass.fqn(), Tier.PLAIN_UNIT);
        boolean hasUncovered = hasUncoveredLinesOrBranches(method, coverage);
        if (existingTestFile.isPresent() && !hasUncovered) {
            return Optional.empty();
        }
        return Optional.of(discovered(productionClass, method, Tier.PLAIN_UNIT, List.of(), coverage,
                existingTestFile));
    }

    /**
     * A Spring-tier unit exists only for a method carrying an unverified framework
     * semantic - never merely because the class is a controller. That restriction is the
     * whole of §7.4's "a class is only lifted above T0 for the specific gaps that T0
     * cannot reach", and it is what keeps a fully-verified controller from costing a
     * context load for nothing.
     *
     * <p><b>{@code selection.minComplexity} deliberately does not apply here.</b> Its
     * rationale in §5 - "a method below this cyclomatic complexity is not worth a
     * generated test" - is reasoning about unit tests of branching logic, and a
     * single-statement handler genuinely has no branching worth a plain test. But its
     * mapping, its request binding and its {@code @Valid} constraints are framework
     * behaviour that a direct call cannot reach at any complexity, which is exactly what
     * gate 2 exists to verify. Applying the branching filter to a gap-driven unit would
     * silently discard the most clear-cut case the Spring tiers exist for.
     */
    private Optional<DiscoveredUnit> springUnitFor(
            ProductionClass productionClass, ProductionMethod method, Tier springTier,
            List<SemanticGap> openGaps, ClassCoverage coverage, DiscoveryInputs inputs) {
        if (springTier == Tier.PLAIN_UNIT || openGaps.isEmpty()) {
            return Optional.empty();
        }
        List<SemanticGap> methodGaps = openGaps.stream()
                .filter(gap -> gap.methodName() == null || gap.methodName().equals(method.name()))
                .toList();
        if (methodGaps.isEmpty()) {
            return Optional.empty();
        }

        Optional<Path> existingTestFile = testClassLocator.locate(productionClass.fqn(), springTier);
        DiscoveredUnit unit = discovered(productionClass, method, springTier, methodGaps, coverage,
                existingTestFile);

        if (!inputs.springTiers().isAvailable(springTier)) {
            String reason = inputs.springTiers().unavailable()
                    .getOrDefault(springTier, springTier + " is not available on this module");
            return Optional.of(new DiscoveredUnit(
                    unit.workUnit().withSkipped(UnitStatus.SKIPPED_TIER_UNAVAILABLE, reason),
                    unit.productionClass(), unit.targetMethod(), unit.testFile(),
                    unit.testClassSimpleName(), unit.gaps(), unit.coverageBefore()));
        }
        return Optional.of(unit);
    }

    private DiscoveredUnit discovered(
            ProductionClass productionClass, ProductionMethod method, Tier tier, List<SemanticGap> gaps,
            ClassCoverage coverage, Optional<Path> existingTestFile) {
        Path testFile = existingTestFile.orElseGet(
                () -> testClassLocator.conventionalPathFor(productionClass.fqn(), tier));
        WorkUnitId id = WorkUnitId.of(productionClass.fqn(), method.signature(), tier);
        WorkUnit workUnit = WorkUnit.pending(id, testFile.toString(),
                productionClass.sourceFile().toString(), hashOf(productionClass.sourceFile()));

        String fileName = testFile.getFileName().toString();
        String testClassSimpleName = fileName.endsWith(".java")
                ? fileName.substring(0, fileName.length() - ".java".length())
                : fileName;
        return new DiscoveredUnit(workUnit, productionClass, method, testFile, testClassSimpleName,
                gaps, coverage);
    }

    /**
     * The class's gaps, minus the ones its existing tests already close (§7.5).
     * Suppression is checked against every test file that could hold them - the tier's own
     * class and the plain one - because a mapping assertion is just as real wherever a
     * developer happened to write it.
     */
    private List<SemanticGap> openGapsOf(ProductionClass productionClass, Tier springTier) {
        if (!config.spring().detectFrameworkSemanticGaps() || springTier == Tier.PLAIN_UNIT) {
            return List.of();
        }
        List<SemanticGap> allGaps = gapScanner.scan(productionClass);
        if (allGaps.isEmpty()) {
            return List.of();
        }
        List<Path> testFiles = new ArrayList<>();
        testClassLocator.locate(productionClass.fqn(), springTier).ifPresent(testFiles::add);
        testClassLocator.locate(productionClass.fqn(), Tier.PLAIN_UNIT).ifPresent(testFiles::add);
        return gapSuppressionDetector.removeSuppressed(allGaps, testFiles);
    }

    /**
     * Whether the method has any line or branch the baseline did not cover. A method the
     * coverage report says nothing about counts as uncovered: "not measured" and "measured
     * as fully covered" are different answers, and only the second justifies skipping.
     */
    private boolean hasUncoveredLinesOrBranches(ProductionMethod method, ClassCoverage coverage) {
        if (coverage == null) {
            return true;
        }
        Optional<MethodCoverage> methodCoverage = coverageJoiner.find(method, coverage);
        return methodCoverage
                .map(found -> found.linesMissed() > 0 || found.branchesMissed() > 0)
                .orElse(true);
    }

    /** §5's {@code selection.order}, within a tier - {@link TierScheduler} orders across tiers. */
    private List<DiscoveredUnit> orderWithinTier(List<DiscoveredUnit> units) {
        SelectionOrder order = config.selection().order();
        if (order == SelectionOrder.DECLARATION) {
            return List.copyOf(units);
        }
        List<DiscoveredUnit> ordered = new ArrayList<>(units);
        if (order == SelectionOrder.ALPHABETICAL) {
            ordered.sort(Comparator.comparing((DiscoveredUnit unit) -> unit.workUnit().className())
                    .thenComparing(unit -> unit.targetMethod().name()));
        } else {
            // LOWEST_COVERAGE_FIRST: the biggest wins first. A method with no measured
            // coverage sorts first of all - it is the least verified thing there is.
            ordered.sort(Comparator.comparingDouble(this::coveredFractionOf)
                    .thenComparing(unit -> unit.workUnit().className())
                    .thenComparing(unit -> unit.targetMethod().name()));
        }
        return List.copyOf(ordered);
    }

    private double coveredFractionOf(DiscoveredUnit unit) {
        if (unit.coverageBefore() == null) {
            return 0.0;
        }
        return coverageJoiner.find(unit.targetMethod(), unit.coverageBefore())
                .map(found -> found.linesTotal() == 0 ? 0.0 : found.linesCovered() / (double) found.linesTotal())
                .orElse(0.0);
    }

    /**
     * The production file hash resume reconciliation compares against (§8.2.3). An
     * unreadable file yields no hash rather than a fabricated one: reconciliation must be
     * able to tell "this source is unchanged" from "we never managed to read it", and a
     * placeholder that happened to match itself next run would silently assert the former.
     */
    private String hashOf(Path sourceFile) {
        try {
            return FileHasher.hash(sourceFile).orElse(null);
        } catch (java.io.IOException e) {
            return null;
        }
    }
}
