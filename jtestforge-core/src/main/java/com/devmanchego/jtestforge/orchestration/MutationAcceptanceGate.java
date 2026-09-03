package com.devmanchego.jtestforge.orchestration;

import com.devmanchego.jtestforge.model.Mutant;
import com.devmanchego.jtestforge.model.MutationReport;
import com.devmanchego.jtestforge.model.MutationStatus;
import com.devmanchego.jtestforge.mutation.MutationException;
import com.devmanchego.jtestforge.mutation.MutationRequest;
import com.devmanchego.jtestforge.mutation.MutationRunner;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Pass 2's step 9 — jtestforge-specification.md §10.1 step 5: re-run PIT scoped to the
 * unit's own class, with the history file, and require that at least one mutant surviving
 * in the baseline is now {@code KILLED}.
 *
 * <p>"Killed" is judged purely by status transition, not by cross-checking PIT's own
 * {@code killingTest} against the unit's newly added method names. The baseline already
 * scoped its {@code targetTests} to every tier-eligible test class, this unit's existing
 * class included, so a mutant that survived there and dies now can only have been killed
 * by something the merge just added - the existing tests were already present, in scope,
 * and evidently insufficient the first time.
 */
public final class MutationAcceptanceGate implements UnitAcceptanceGate {

    private final MutationRunner mutationRunner;
    private final Path modulePath;
    private final Path historyFile;
    private final String mutators;
    private final Duration timeout;

    public MutationAcceptanceGate(MutationRunner mutationRunner, Path modulePath, Path historyFile,
                                  String mutators, Duration timeout) {
        this.mutationRunner = Objects.requireNonNull(mutationRunner, "mutationRunner");
        this.modulePath = Objects.requireNonNull(modulePath, "modulePath");
        this.historyFile = historyFile;
        this.mutators = Objects.requireNonNull(mutators, "mutators");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
    }

    @Override
    public AcceptanceVerdict evaluate(UnitContext context, MergedCandidates merged) {
        if (context.targetMutants().isEmpty()) {
            return AcceptanceVerdict.discard("this unit records no target mutants to verify against");
        }

        MutationRequest request = new MutationRequest(modulePath,
                List.of(context.productionClass().fqn()), List.of(testClassFqnOf(context)),
                historyFile, mutators, timeout);

        MutationReport report;
        try {
            report = mutationRunner.run(request);
        } catch (MutationException e) {
            return AcceptanceVerdict.discard("scoped PIT verification failed: " + e.getMessage());
        }

        Set<String> baselineSurvivingIds = context.targetMutants().stream().map(Mutant::stableId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        List<String> newlyKilled = report.mutants().stream()
                .filter(mutant -> mutant.status() == MutationStatus.KILLED)
                .map(Mutant::stableId)
                .filter(baselineSurvivingIds::contains)
                .toList();

        if (newlyKilled.isEmpty()) {
            return AcceptanceVerdict.discard(
                    "the scoped re-run killed none of the " + context.targetMutants().size()
                            + " mutant(s) this unit targeted - they remain surviving or uncovered");
        }
        return AcceptanceVerdict.keep(
                "killed " + newlyKilled.size() + " previously-surviving mutant(s)",
                List.of(), newlyKilled, 0, 0);
    }

    private String testClassFqnOf(UnitContext context) {
        if (context.testClassInfo() != null) {
            String packageName = context.testClassInfo().packageName();
            return packageName.isEmpty()
                    ? context.testClassInfo().className()
                    : packageName + "." + context.testClassInfo().className();
        }
        String packageName = context.productionClass().packageName();
        return packageName.isEmpty() ? context.testClassSimpleName() : packageName + "." + context.testClassSimpleName();
    }
}
