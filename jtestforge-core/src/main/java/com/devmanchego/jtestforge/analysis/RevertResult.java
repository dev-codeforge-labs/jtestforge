package com.devmanchego.jtestforge.analysis;

import java.util.List;

/**
 * What a revert actually removed.
 *
 * @param removedTestNames methods removed from the test class
 * @param removedImports   imports removed because the unit added them and nothing
 *                         remaining in the file still refers to them
 */
public record RevertResult(List<String> removedTestNames, List<String> removedImports) {

    public RevertResult {
        removedTestNames = removedTestNames == null ? List.of() : List.copyOf(removedTestNames);
        removedImports = removedImports == null ? List.of() : List.copyOf(removedImports);
    }
}
