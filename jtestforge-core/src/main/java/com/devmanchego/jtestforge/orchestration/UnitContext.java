package com.devmanchego.jtestforge.orchestration;

import com.devmanchego.jtestforge.model.ClassCoverage;
import com.devmanchego.jtestforge.model.MockBeanDeclaration;
import com.devmanchego.jtestforge.model.Mutant;
import com.devmanchego.jtestforge.model.ProductionClass;
import com.devmanchego.jtestforge.model.ProductionMethod;
import com.devmanchego.jtestforge.model.SemanticGap;
import com.devmanchego.jtestforge.model.SpringStackFacts;
import com.devmanchego.jtestforge.model.SpringStereotype;
import com.devmanchego.jtestforge.model.TestClassInfo;
import com.devmanchego.jtestforge.model.TestFrameworkVersions;
import com.devmanchego.jtestforge.model.WorkUnit;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Everything one work unit needs to be processed, gathered once by the engine.
 *
 * <p>Assembled up front rather than fetched lazily inside the loop because most of it is
 * expensive to obtain (a source scan, a coverage report, a dependency list) and shared
 * across every unit of the same class - fetching per unit would re-read the same files
 * once per method.
 *
 * @param testFile             absolute path of the test file this unit writes to
 * @param testClassSimpleName  simple name of that test class, for {@code -Dtest=} scoping
 * @param testClassInfo        what the test class already contains, {@code null} if new
 * @param gaps                 framework-semantic gaps this unit was raised to close, empty
 *                             for a coverage-driven unit
 * @param coverageBefore       the class's coverage before this unit ran, {@code null} when
 *                             the baseline produced none for it
 * @param targetMutants        pass 2 only: the baseline-surviving mutants this unit exists
 *                             to kill, empty for a coverage/gap-driven pass 1 unit. Their
 *                             translated behavioural descriptions (§10.2) are what render
 *                             into {@code {{BEHAVIOUR_GAPS}}}, and their own identities are
 *                             what {@code MutationAcceptanceGate} checks after the scoped
 *                             re-run - both derived from this one list, never duplicated
 */
public record UnitContext(
        WorkUnit unit,
        ProductionClass productionClass,
        ProductionMethod targetMethod,
        Path testFile,
        String testClassSimpleName,
        TestClassInfo testClassInfo,
        List<SemanticGap> gaps,
        ClassCoverage coverageBefore,
        SpringStackFacts springFacts,
        TestFrameworkVersions frameworkVersions,
        List<MockBeanDeclaration> mockBeans,
        SpringStereotype stereotype,
        List<Mutant> targetMutants) {

    public UnitContext {
        Objects.requireNonNull(unit, "unit");
        Objects.requireNonNull(productionClass, "productionClass");
        Objects.requireNonNull(targetMethod, "targetMethod");
        Objects.requireNonNull(testFile, "testFile");
        gaps = gaps == null ? List.of() : List.copyOf(gaps);
        mockBeans = mockBeans == null ? List.of() : List.copyOf(mockBeans);
        springFacts = springFacts == null ? SpringStackFacts.noSpring() : springFacts;
        frameworkVersions = frameworkVersions == null ? TestFrameworkVersions.none() : frameworkVersions;
        stereotype = stereotype == null ? SpringStereotype.NONE : stereotype;
        targetMutants = targetMutants == null ? List.of() : List.copyOf(targetMutants);
    }

    /**
     * A pass-1 context, with no target mutants. Kept as the primary constructor call
     * shape for pass 1's own call sites, so they need not spell out an empty list.
     */
    public UnitContext(WorkUnit unit, ProductionClass productionClass, ProductionMethod targetMethod,
                       Path testFile, String testClassSimpleName, TestClassInfo testClassInfo,
                       List<SemanticGap> gaps, ClassCoverage coverageBefore, SpringStackFacts springFacts,
                       TestFrameworkVersions frameworkVersions, List<MockBeanDeclaration> mockBeans,
                       SpringStereotype stereotype) {
        this(unit, productionClass, targetMethod, testFile, testClassSimpleName, testClassInfo, gaps,
                coverageBefore, springFacts, frameworkVersions, mockBeans, stereotype, List.of());
    }

    /** Whether the test class already exists, deciding which generation template applies. */
    public boolean testClassExists() {
        return testClassInfo != null;
    }

    /** Pass 2 only: whether this unit targets mutants rather than coverage/gaps. */
    public boolean isMutationUnit() {
        return !targetMutants.isEmpty();
    }

    /**
     * Rebuilt after a mock-bean escalation (§7.6) synthesises new fields into the test
     * file: the class the model sees on its retry must reflect what is now actually on
     * disk, or it would be prompted against a class that no longer matches the file it is
     * about to be merged into.
     */
    public UnitContext withTestClassInfo(TestClassInfo newTestClassInfo) {
        return new UnitContext(unit, productionClass, targetMethod, testFile, testClassSimpleName,
                newTestClassInfo, gaps, coverageBefore, springFacts, frameworkVersions, mockBeans, stereotype,
                targetMutants);
    }

    /**
     * Swaps in a new {@link WorkUnit} - typically the same unit with {@code attempts}
     * incremented - while keeping everything else about the unit's context unchanged.
     * {@code HardenEngine} uses this to give each retry of the same mutant group its own
     * attempt count, which is what keeps their transcripts from overwriting each other
     * (see {@code DefaultUnitProcessor.transcriptEpoch}).
     */
    public UnitContext withUnit(WorkUnit newUnit) {
        return new UnitContext(newUnit, productionClass, targetMethod, testFile, testClassSimpleName,
                testClassInfo, gaps, coverageBefore, springFacts, frameworkVersions, mockBeans, stereotype,
                targetMutants);
    }
}
