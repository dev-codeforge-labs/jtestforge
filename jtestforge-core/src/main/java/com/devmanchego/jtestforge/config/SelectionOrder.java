package com.devmanchego.jtestforge.config;

import com.fasterxml.jackson.annotation.JsonProperty;

/** {@code selection.order} — jtestforge-specification.md §5. */
public enum SelectionOrder {
    @JsonProperty("lowestCoverageFirst") LOWEST_COVERAGE_FIRST,
    @JsonProperty("alphabetical") ALPHABETICAL,
    @JsonProperty("declaration") DECLARATION
}
