package com.devmanchego.jtestforge.analysis;

import java.util.List;
import java.util.Optional;

/**
 * Outcome of merging generated candidates into a test class.
 *
 * <p>A rejection is not an error: it is the merger refusing to write something unsafe,
 * and the engine records the reason and moves on. Nothing has been written to disk when
 * {@link #isRejected()} is true.
 *
 * @param addedTestNames  methods actually written, in the order they were inserted
 * @param addedImports    imports actually introduced - recorded so the revert can remove
 *                        exactly these and leave the developer's own imports alone
 * @param rejectionReason why nothing was written, if the merge was refused
 */
public record MergeResult(List<String> addedTestNames, List<String> addedImports, String rejectionReason) {

    public MergeResult {
        addedTestNames = addedTestNames == null ? List.of() : List.copyOf(addedTestNames);
        addedImports = addedImports == null ? List.of() : List.copyOf(addedImports);
    }

    public static MergeResult merged(List<String> addedTestNames, List<String> addedImports) {
        return new MergeResult(addedTestNames, addedImports, null);
    }

    public static MergeResult rejected(String reason) {
        return new MergeResult(List.of(), List.of(), reason);
    }

    public boolean isRejected() {
        return rejectionReason != null;
    }

    public Optional<String> rejection() {
        return Optional.ofNullable(rejectionReason);
    }
}
