package com.devmanchego.jtestforge.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Objects;
import java.util.Optional;

/**
 * Identity of one work unit — jtestforge-specification.md §8.1.
 *
 * <p>Textual form, which is also its JSON representation:
 * <pre>
 *   com.acme.PaymentService#applyFee(java.math.BigDecimal,java.util.Currency)@PLAIN_UNIT
 *   com.acme.PaymentService#applyFee(...)@PLAIN_UNIT::CONDITIONALS_BOUNDARY@47
 * </pre>
 *
 * <p><b>The tier is part of the identity, not a detail of it.</b> The same production
 * method legitimately holds more than one unit — a {@code PLAIN_UNIT} for the branching
 * inside its body and a {@code WEB_SLICE} for the mapping and validation contract around
 * it (§7.4). If the tier were excluded, the second unit would overwrite the first in the
 * state file and one of the two would silently never run.
 *
 * @param className       fully-qualified production class name
 * @param methodSignature method name with its parameter types, e.g. {@code applyFee(BigDecimal,Currency)}
 * @param tier            the tier this unit is generated at
 * @param mutantGroup     pass 2 only: the mutant group this unit targets, e.g.
 *                        {@code CONDITIONALS_BOUNDARY@47}; {@code null} in pass 1
 */
public record WorkUnitId(String className, String methodSignature, Tier tier, String mutantGroup) {

    private static final String METHOD_SEPARATOR = "#";
    private static final String TIER_SEPARATOR = "@";
    private static final String MUTANT_SEPARATOR = "::";

    public WorkUnitId {
        Objects.requireNonNull(className, "className");
        Objects.requireNonNull(methodSignature, "methodSignature");
        Objects.requireNonNull(tier, "tier");
        if (className.isBlank()) {
            throw new IllegalArgumentException("className must not be blank");
        }
        if (methodSignature.isBlank()) {
            throw new IllegalArgumentException("methodSignature must not be blank");
        }
        if (methodSignature.contains(TIER_SEPARATOR)) {
            // Parsing splits the tier off at the last '@' before any '::', which is only
            // unambiguous while method signatures contain no '@' of their own.
            throw new IllegalArgumentException(
                    "methodSignature must not contain '@': " + methodSignature);
        }
    }

    /** Creates a pass 1 (coverage / semantic-gap) unit id. */
    public static WorkUnitId of(String className, String methodSignature, Tier tier) {
        return new WorkUnitId(className, methodSignature, tier, null);
    }

    public Optional<String> mutantGroupIfPresent() {
        return Optional.ofNullable(mutantGroup);
    }

    /** Returns this id extended with a pass 2 mutant group. */
    public WorkUnitId withMutantGroup(String group) {
        return new WorkUnitId(className, methodSignature, tier, Objects.requireNonNull(group, "group"));
    }

    @JsonValue
    public String format() {
        String base = className + METHOD_SEPARATOR + methodSignature + TIER_SEPARATOR + tier.name();
        return mutantGroup == null ? base : base + MUTANT_SEPARATOR + mutantGroup;
    }

    @JsonCreator
    public static WorkUnitId parse(String text) {
        Objects.requireNonNull(text, "text");

        String head = text;
        String mutantGroup = null;
        int mutantIndex = text.indexOf(MUTANT_SEPARATOR);
        if (mutantIndex >= 0) {
            head = text.substring(0, mutantIndex);
            mutantGroup = text.substring(mutantIndex + MUTANT_SEPARATOR.length());
        }

        int methodIndex = head.indexOf(METHOD_SEPARATOR);
        if (methodIndex < 0) {
            throw new IllegalArgumentException("Malformed work unit id, missing '#': " + text);
        }
        String className = head.substring(0, methodIndex);
        String remainder = head.substring(methodIndex + METHOD_SEPARATOR.length());

        int tierIndex = remainder.lastIndexOf(TIER_SEPARATOR);
        if (tierIndex < 0) {
            throw new IllegalArgumentException("Malformed work unit id, missing tier: " + text);
        }
        String methodSignature = remainder.substring(0, tierIndex);
        String tierName = remainder.substring(tierIndex + TIER_SEPARATOR.length());

        Tier tier;
        try {
            tier = Tier.valueOf(tierName);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Malformed work unit id, unknown tier '" + tierName + "': " + text, e);
        }
        return new WorkUnitId(className, methodSignature, tier, mutantGroup);
    }

    @Override
    public String toString() {
        return format();
    }
}
