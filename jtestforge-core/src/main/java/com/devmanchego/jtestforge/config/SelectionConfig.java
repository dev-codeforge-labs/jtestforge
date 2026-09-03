package com.devmanchego.jtestforge.config;

import java.util.List;

/** {@code selection} block — jtestforge-specification.md §5. */
public record SelectionConfig(
        List<String> includePackages,
        List<String> excludePackages,
        List<String> excludeClasses,
        List<String> excludeAnnotations,
        Integer minComplexity,
        Boolean includePrivateMethods,
        SelectionOrder order) {

    public SelectionConfig {
        includePackages = includePackages == null ? List.of() : List.copyOf(includePackages);
        excludePackages = excludePackages == null ? List.of() : List.copyOf(excludePackages);
        excludeClasses = excludeClasses == null ? List.of() : List.copyOf(excludeClasses);
        excludeAnnotations = excludeAnnotations == null ? List.of() : List.copyOf(excludeAnnotations);
        minComplexity = minComplexity == null ? 2 : minComplexity;
        includePrivateMethods = includePrivateMethods == null ? Boolean.FALSE : includePrivateMethods;
        order = order == null ? SelectionOrder.LOWEST_COVERAGE_FIRST : order;
    }
}
