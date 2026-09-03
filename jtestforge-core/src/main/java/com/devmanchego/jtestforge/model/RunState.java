package com.devmanchego.jtestforge.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The complete persisted state of one run — jtestforge-specification.md §8. Serialised to
 * {@code <stateDir>/state.json}, and the sole source the run report is rendered from
 * (§15); there is deliberately no second bookkeeping artifact that could disagree with it.
 *
 * @param schemaVersion  bumped whenever this record's JSON shape changes incompatibly
 * @param runId          identifies this run in reports and transcript directories
 * @param startedAt      when the run began
 * @param updatedAt      stamped by {@code StateStore} on every save
 * @param phase          which pass owns this state
 * @param modulePath     the module the run operates on
 * @param configHash     hash of the config used; a change invalidates the baseline (§8.2)
 * @param providerId     which AI provider produced the run's output
 * @param baseline       measurements taken before generation, {@code null} until measured
 * @param springTiers    tier availability and context-load accounting
 * @param units          every work unit, in execution order
 */
public record RunState(
        int schemaVersion,
        String runId,
        Instant startedAt,
        Instant updatedAt,
        Phase phase,
        String modulePath,
        String configHash,
        String providerId,
        Baseline baseline,
        SpringTierState springTiers,
        List<WorkUnit> units) {

    /** Current schema version. {@code StateStore} refuses any other value. */
    public static final int CURRENT_SCHEMA_VERSION = 1;

    public RunState {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(phase, "phase");
        units = units == null ? List.of() : List.copyOf(units);
    }

    public static RunState startNew(
            String runId, Instant startedAt, Phase phase, String modulePath,
            String configHash, String providerId, SpringTierState springTiers, List<WorkUnit> units) {
        return new RunState(CURRENT_SCHEMA_VERSION, runId, startedAt, startedAt, phase,
                modulePath, configHash, providerId, null, springTiers, units);
    }

    public Optional<WorkUnit> unit(WorkUnitId id) {
        return units.stream().filter(unit -> unit.id().equals(id)).findFirst();
    }

    public List<WorkUnit> unitsWithStatus(UnitStatus status) {
        return units.stream().filter(unit -> unit.status() == status).toList();
    }

    /**
     * Replaces the unit with the same id, preserving execution order. A unit id not
     * already present is an error rather than an append: units are discovered up front by
     * the pass engine, so an unknown id at update time means the caller built an id that
     * does not match the one discovery produced - exactly the kind of mismatch that would
     * otherwise silently create a duplicate entry.
     */
    public RunState withUnit(WorkUnit updated) {
        Objects.requireNonNull(updated, "updated");
        List<WorkUnit> replaced = new ArrayList<>(units.size());
        boolean found = false;
        for (WorkUnit existing : units) {
            if (existing.id().equals(updated.id())) {
                replaced.add(updated);
                found = true;
            } else {
                replaced.add(existing);
            }
        }
        if (!found) {
            throw new IllegalArgumentException("No such work unit in this run: " + updated.id());
        }
        return withUnits(replaced);
    }

    public RunState withUnits(List<WorkUnit> newUnits) {
        return new RunState(schemaVersion, runId, startedAt, updatedAt, phase, modulePath,
                configHash, providerId, baseline, springTiers, newUnits);
    }

    public RunState withUpdatedAt(Instant newUpdatedAt) {
        return new RunState(schemaVersion, runId, startedAt, newUpdatedAt, phase, modulePath,
                configHash, providerId, baseline, springTiers, units);
    }

    public RunState withBaseline(Baseline newBaseline) {
        return new RunState(schemaVersion, runId, startedAt, updatedAt, phase, modulePath,
                configHash, providerId, newBaseline, springTiers, units);
    }

    public RunState withConfigHash(String newConfigHash) {
        return new RunState(schemaVersion, runId, startedAt, updatedAt, phase, modulePath,
                newConfigHash, providerId, baseline, springTiers, units);
    }

    public RunState withSpringTiers(SpringTierState newSpringTiers) {
        return new RunState(schemaVersion, runId, startedAt, updatedAt, phase, modulePath,
                configHash, providerId, baseline, newSpringTiers, units);
    }

    /** Count of units per status, for the status command and the run report. */
    public Map<UnitStatus, Integer> statusCounts() {
        Map<UnitStatus, Integer> counts = new LinkedHashMap<>();
        for (WorkUnit unit : units) {
            counts.merge(unit.status(), 1, Integer::sum);
        }
        return Map.copyOf(counts);
    }
}
