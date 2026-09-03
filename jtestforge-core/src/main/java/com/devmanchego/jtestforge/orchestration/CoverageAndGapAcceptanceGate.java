package com.devmanchego.jtestforge.orchestration;

import com.devmanchego.jtestforge.build.ModuleBuild;
import com.devmanchego.jtestforge.coverage.CoverageDeltaCalculator;
import com.devmanchego.jtestforge.model.ClassCoverage;
import com.devmanchego.jtestforge.model.CoverageDelta;
import com.devmanchego.jtestforge.model.SemanticGap;
import com.devmanchego.jtestforge.model.TestCandidate;
import com.devmanchego.jtestforge.spring.AssertionEvidence;
import com.devmanchego.jtestforge.spring.AssertionEvidenceScanner;
import com.devmanchego.jtestforge.spring.GapSuppressionDetector;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.body.BodyDeclaration;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Pass 1's step 9 — jtestforge-specification.md §9.4 step 9: a coverage delta, or a closed
 * framework-semantic gap, evaluated per tier by {@link ValueGate} (§1's asymmetry between
 * the plain tier and the Spring tiers).
 */
public final class CoverageAndGapAcceptanceGate implements UnitAcceptanceGate {

    private final ModuleBuild moduleBuild;
    private final CoverageDeltaCalculator coverageDeltaCalculator;
    private final ValueGate valueGate;

    private final AssertionEvidenceScanner evidenceScanner = new AssertionEvidenceScanner();
    private final GapSuppressionDetector gapMatcher = new GapSuppressionDetector();
    private final JavaParser javaParser = new JavaParser(new ParserConfiguration()
            .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21));

    public CoverageAndGapAcceptanceGate(
            ModuleBuild moduleBuild, CoverageDeltaCalculator coverageDeltaCalculator, ValueGate valueGate) {
        this.moduleBuild = Objects.requireNonNull(moduleBuild, "moduleBuild");
        this.coverageDeltaCalculator = Objects.requireNonNull(coverageDeltaCalculator, "coverageDeltaCalculator");
        this.valueGate = Objects.requireNonNull(valueGate, "valueGate");
    }

    @Override
    public AcceptanceVerdict evaluate(UnitContext context, MergedCandidates merged) {
        CoverageDelta delta = measureCoverageDelta(context);
        List<SemanticGap> closedGaps = gapsActuallyAssertedBy(merged, context);

        ValueVerdict verdict = valueGate.evaluate(context.unit().tier(), delta, closedGaps);
        if (!verdict.keep()) {
            return AcceptanceVerdict.discard(verdict.reason());
        }
        return AcceptanceVerdict.keep(verdict.reason(), verdict.gapsClosed(), List.of(),
                delta == null ? 0 : delta.linesCoveredDelta(),
                delta == null ? 0 : delta.branchesCoveredDelta());
    }

    private CoverageDelta measureCoverageDelta(UnitContext context) {
        if (context.coverageBefore() == null) {
            return null;
        }
        Optional<ClassCoverage> after = moduleBuild.measureCoverage(context.productionClass().fqn());
        return after.map(coverage -> coverageDeltaCalculator.delta(
                context.targetMethod(), context.coverageBefore(), coverage)).orElse(null);
    }

    /**
     * Which of the unit's gaps the kept tests actually demonstrate the required shape for.
     *
     * <p>Measured from the merged code rather than assumed from the prompt. A unit is
     * generated <em>for</em> a set of gaps, but whether it closed them is a property of
     * what the model wrote and what then passed - and recording a gap as closed when it
     * is not would leave the contract unverified while the report claims otherwise.
     */
    private List<SemanticGap> gapsActuallyAssertedBy(MergedCandidates merged, UnitContext context) {
        if (context.gaps().isEmpty()) {
            return List.of();
        }
        List<AssertionEvidence> evidence = new ArrayList<>();
        for (TestCandidate candidate : merged.candidates()) {
            javaParser.parseBodyDeclaration(candidate.sourceCode()).getResult()
                    .filter(BodyDeclaration::isMethodDeclaration)
                    .ifPresent(declaration -> evidence.addAll(evidenceScanner.scan(declaration)));
        }
        return context.gaps().stream()
                .filter(gap -> evidence.stream().anyMatch(found -> gapMatcher.closes(found, gap)))
                .toList();
    }
}
