package com.devmanchego.jtestforge.model;

import java.util.Objects;

/**
 * One method's coverage counters, from one {@code <method>} element of a JaCoCo class
 * report — jtestforge-specification.md §9.2, §9.4.
 *
 * <p>{@code jvmDescriptor} is what disambiguates overloads: JaCoCo identifies a method by
 * {@code name} + {@code desc} (e.g. {@code add}/{@code (II)I} vs {@code add}/{@code (DD)D}),
 * not by name alone. Matching this to a {@code ProductionMethod} from the AST scan is
 * {@code CoverageMethodJoiner}'s job, not this record's.
 *
 * <p>A counter type JaCoCo omitted entirely (it omits a counter type whose count is zero
 * for that method, rather than writing zero) reads as zero here - see
 * {@code JacocoReportParser}.
 *
 * @param name           method name as JaCoCo reports it ({@code <init>} for constructors,
 *                       {@code <clinit>} for static initializers - {@code CoverageMethodJoiner}
 *                       excludes both, since neither has a corresponding {@code ProductionMethod})
 * @param jvmDescriptor  the JVM method descriptor, e.g. {@code (Ljava/lang/String;I)V}
 * @param startLine      first source line JaCoCo attributes to this method
 */
public record MethodCoverage(
        String name,
        String jvmDescriptor,
        int startLine,
        int instructionsMissed,
        int instructionsCovered,
        int linesMissed,
        int linesCovered,
        int branchesMissed,
        int branchesCovered) {

    public MethodCoverage {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(jvmDescriptor, "jvmDescriptor");
    }

    public boolean isConstructorOrStaticInitializer() {
        return name.equals("<init>") || name.equals("<clinit>");
    }

    public int linesTotal() {
        return linesMissed + linesCovered;
    }

    public int branchesTotal() {
        return branchesMissed + branchesCovered;
    }
}
