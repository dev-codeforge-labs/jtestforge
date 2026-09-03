package com.devmanchego.jtestforge.model;

import java.util.Objects;

/**
 * One test method's outcome, read from {@code target/surefire-reports/*.xml} —
 * jtestforge-specification.md §13.1.
 *
 * <p>Read from the XML rather than the console: the XML schema is stable across Surefire
 * versions and locales, the console format is not.
 *
 * @param className        fully-qualified test class name, as Surefire reports it
 * @param testName         test method name
 * @param outcome          how the test resolved
 * @param failureType      the exception's class name, or {@code null} if none
 * @param failureMessage   the exception's message, or {@code null} if none
 * @param stackTraceHead   the first few lines of the stack trace, capped for the prompt,
 *                         or {@code null} if none
 * @param timeSeconds      reported execution time
 */
public record SurefireTestResult(
        String className,
        String testName,
        TestOutcome outcome,
        String failureType,
        String failureMessage,
        String stackTraceHead,
        double timeSeconds) {

    public SurefireTestResult {
        Objects.requireNonNull(className, "className");
        Objects.requireNonNull(testName, "testName");
        Objects.requireNonNull(outcome, "outcome");
    }

    public boolean isFailure() {
        return outcome == TestOutcome.ASSERTION_FAILURE
                || outcome == TestOutcome.CONTEXT_LOAD_FAILURE
                || outcome == TestOutcome.ERROR;
    }
}
