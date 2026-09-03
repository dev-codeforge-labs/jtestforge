package com.devmanchego.jtestforge.provider;

import com.devmanchego.jtestforge.model.TestCandidate;

import java.util.List;

/**
 * Outcome of parsing one AI response against the §6.2 contract.
 *
 * <p>Exactly one of two states: either {@link #violation()} is present and
 * {@code candidates}/{@code dropped} are both empty (the whole response was unusable),
 * or {@code violation} is {@code null} and {@code candidates} holds whatever survived
 * individual filtering - possibly empty, if every declaration was dropped.
 *
 * @param candidates methods that passed every per-declaration check, ready for
 *                    {@code TestClassMerger}
 * @param dropped    everything discarded from an otherwise-usable response, with reasons
 * @param violation  the fatal defect, or {@code null} if the response's shape was valid
 */
public record ResponseParseResult(
        List<TestCandidate> candidates, List<DroppedDeclaration> dropped, ContractViolation violation) {

    public ResponseParseResult {
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
        dropped = dropped == null ? List.of() : List.copyOf(dropped);
    }

    public static ResponseParseResult fatal(ContractViolation violation) {
        return new ResponseParseResult(List.of(), List.of(), violation);
    }

    public static ResponseParseResult usable(List<TestCandidate> candidates, List<DroppedDeclaration> dropped) {
        return new ResponseParseResult(candidates, dropped, null);
    }

    public boolean isFatal() {
        return violation != null;
    }
}
