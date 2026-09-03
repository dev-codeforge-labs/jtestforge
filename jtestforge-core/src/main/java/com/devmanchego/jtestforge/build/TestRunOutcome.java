package com.devmanchego.jtestforge.build;

import com.devmanchego.jtestforge.model.SurefireTestResult;

import java.util.List;

/**
 * Result of running tests — jtestforge-specification.md §9.4 step 8, and §9.5's final
 * full-suite verification.
 *
 * @param results every test Surefire reported, whether it passed or not
 */
public record TestRunOutcome(List<SurefireTestResult> results) {

    public TestRunOutcome {
        results = results == null ? List.of() : List.copyOf(results);
    }

    public static TestRunOutcome allPassing(List<SurefireTestResult> results) {
        return new TestRunOutcome(results);
    }

    public boolean allPassed() {
        return results.stream().noneMatch(SurefireTestResult::isFailure);
    }

    public List<SurefireTestResult> failures() {
        return results.stream().filter(SurefireTestResult::isFailure).toList();
    }
}
