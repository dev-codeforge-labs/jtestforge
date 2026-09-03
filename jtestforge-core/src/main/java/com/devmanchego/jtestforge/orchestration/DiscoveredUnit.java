package com.devmanchego.jtestforge.orchestration;

import com.devmanchego.jtestforge.model.ClassCoverage;
import com.devmanchego.jtestforge.model.ProductionClass;
import com.devmanchego.jtestforge.model.ProductionMethod;
import com.devmanchego.jtestforge.model.SemanticGap;
import com.devmanchego.jtestforge.model.WorkUnit;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * One discovered work unit, paired with everything needed to later build its
 * {@link UnitContext} without re-scanning anything.
 *
 * <p>The pairing matters: {@link GenerateEngine} takes a {@code Function<WorkUnit,
 * UnitContext>} precisely so a unit's view of its test class is read fresh at the moment
 * it runs (earlier units in the same class may have just written to it), but everything
 * <em>else</em> - the production class, the target method, its gaps, its baseline coverage -
 * was already determined during discovery and must not be recomputed per unit.
 */
public record DiscoveredUnit(
        WorkUnit workUnit,
        ProductionClass productionClass,
        ProductionMethod targetMethod,
        Path testFile,
        String testClassSimpleName,
        List<SemanticGap> gaps,
        ClassCoverage coverageBefore) {

    public DiscoveredUnit {
        Objects.requireNonNull(workUnit, "workUnit");
        Objects.requireNonNull(productionClass, "productionClass");
        Objects.requireNonNull(targetMethod, "targetMethod");
        Objects.requireNonNull(testFile, "testFile");
        gaps = gaps == null ? List.of() : List.copyOf(gaps);
    }
}
