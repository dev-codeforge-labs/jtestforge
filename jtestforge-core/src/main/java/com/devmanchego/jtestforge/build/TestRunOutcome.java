package com.devmanchego.jtestforge.build;

import com.devmanchego.jtestforge.model.SurefireTestResult;

import java.util.List;

/**
 * Result of running tests — jtestforge-specification.md §9.4 step 8, and §9.5's final
 * full-suite verification.
 *
 * <p>Carries whether MAVEN itself succeeded, not only what Surefire reported. A build that
 * dies before the test phase - a compilation error anywhere in the module, an annotation
 * processor, an unresolvable plugin - produces no Surefire reports at all, so judging it on
 * reports alone reads as "nothing failed". That is how a module which does not compile used
 * to sail through the §2 preflight, after which every generated test was blamed for a
 * compilation error that predated the run.
 *
 * @param results        every test Surefire reported, whether it passed or not
 * @param buildSucceeded whether the Maven invocation itself exited cleanly
 * @param buildLog       the build's output, for reporting a failure that has no Surefire
 *                       result to point at
 */
public record TestRunOutcome(List<SurefireTestResult> results, boolean buildSucceeded, String buildLog) {

    public TestRunOutcome {
        results = results == null ? List.of() : List.copyOf(results);
        buildLog = buildLog == null ? "" : buildLog;
    }

    /** A build that ran to completion; whether its tests passed is then up to {@link #allPassed()}. */
    public TestRunOutcome(List<SurefireTestResult> results) {
        this(results, true, "");
    }

    public static TestRunOutcome allPassing(List<SurefireTestResult> results) {
        return new TestRunOutcome(results);
    }

    /**
     * Whether Maven failed WITHOUT reaching the tests, which is a different problem from a
     * red suite and needs a different message: {@code mvn test} exits non-zero for a
     * failing test too, but that failure has Surefire results behind it to report.
     */
    public boolean buildFailedBeforeTests() {
        return !buildSucceeded && results.isEmpty();
    }

    public boolean allPassed() {
        return !buildFailedBeforeTests() && results.stream().noneMatch(SurefireTestResult::isFailure);
    }

    /**
     * What to report for a build that failed with no Surefire result to name. The first few
     * {@code ERROR} lines are what a developer would read first anyway - seeing
     * "assertEquals is ambiguous" straight away beats being told to go open a log file.
     */
    public List<String> buildFailureSummary() {
        List<String> errors = buildLog.lines()
                .map(String::trim)
                .filter(line -> line.contains("ERROR")
                        && !line.contains("[Help 1]")
                        && !line.toLowerCase(java.util.Locale.ROOT).contains("re-run maven"))
                .distinct()
                .limit(5)
                .toList();
        return errors.isEmpty()
                ? List.of("mvn test failed before any test ran (no javac diagnostic in the output)")
                : errors;
    }

    public List<SurefireTestResult> failures() {
        return results.stream().filter(SurefireTestResult::isFailure).toList();
    }
}
