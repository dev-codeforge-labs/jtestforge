package com.devmanchego.jtestforge.orchestration;

import com.devmanchego.jtestforge.build.CompileOutcome;
import com.devmanchego.jtestforge.build.ModuleBuild;
import com.devmanchego.jtestforge.build.TestRunOutcome;
import com.devmanchego.jtestforge.model.ClassCoverage;
import com.devmanchego.jtestforge.model.CompilerError;
import com.devmanchego.jtestforge.model.SurefireTestResult;
import com.devmanchego.jtestforge.model.TestOutcome;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;

/**
 * A scripted {@link ModuleBuild} for the engine's tests.
 *
 * <p>Faked at the port rather than at Maven's command line. Imitating {@code mvn}'s goals,
 * system properties and report-file layout would mean the test spent its effort asserting
 * that the fake behaves like Maven - which proves nothing about the engine, the thing
 * actually under test here. The real Maven wiring is exercised separately, against a real
 * Maven, in {@code MavenRunnerTest} and {@code MavenClasspathResolverTest}.
 */
final class FakeModuleBuild implements ModuleBuild {

    private final Deque<CompileOutcome> compileOutcomes = new ArrayDeque<>();
    private final Deque<TestRunOutcome> scopedRunOutcomes = new ArrayDeque<>();
    private final Deque<TestRunOutcome> fullSuiteOutcomes = new ArrayDeque<>();
    private final Deque<Optional<ClassCoverage>> coverageOutcomes = new ArrayDeque<>();

    private final List<String> calls = new ArrayList<>();

    FakeModuleBuild compilesSuccessfully(int times) {
        for (int i = 0; i < times; i++) {
            compileOutcomes.add(CompileOutcome.success());
        }
        return this;
    }

    FakeModuleBuild failsToCompile(String message) {
        compileOutcomes.add(CompileOutcome.failure(List.of(
                new CompilerError("PaymentServiceTest.java", 42, 9, message)), "[ERROR] " + message));
        return this;
    }

    /** A build failure {@code CompilerErrorParser} could not extract any diagnostic from. */
    FakeModuleBuild failsToCompileWithoutDiagnostics(String rawLog) {
        compileOutcomes.add(CompileOutcome.failure(List.of(), rawLog));
        return this;
    }

    FakeModuleBuild scopedTestsPass() {
        scopedRunOutcomes.add(new TestRunOutcome(List.of(passing("aGeneratedTest"))));
        return this;
    }

    FakeModuleBuild scopedTestsFail(String message) {
        scopedRunOutcomes.add(new TestRunOutcome(List.of(failing("aGeneratedTest", message))));
        return this;
    }

    FakeModuleBuild fullSuiteGreen(int times) {
        for (int i = 0; i < times; i++) {
            fullSuiteOutcomes.add(new TestRunOutcome(List.of(passing("anExistingTest"))));
        }
        return this;
    }

    FakeModuleBuild fullSuiteRed() {
        fullSuiteOutcomes.add(new TestRunOutcome(List.of(failing("anExistingTest", "interaction"))));
        return this;
    }

    /** Maven fails before the test phase, so Surefire reports nothing at all - a module that does not compile. */
    FakeModuleBuild fullSuiteBuildFails(String buildLog) {
        fullSuiteOutcomes.add(new TestRunOutcome(List.of(), false, buildLog));
        return this;
    }

    /** The scoped run fails without Surefire reporting a single result - the tests never ran. */
    FakeModuleBuild scopedRunNeverRan(String buildLog) {
        scopedRunOutcomes.add(new TestRunOutcome(List.of(), false, buildLog));
        return this;
    }

    FakeModuleBuild coverage(ClassCoverage coverage) {
        coverageOutcomes.add(Optional.ofNullable(coverage));
        return this;
    }

    List<String> calls() {
        return List.copyOf(calls);
    }

    @Override
    public CompileOutcome compileTests() {
        calls.add("compileTests");
        CompileOutcome next = compileOutcomes.poll();
        return next != null ? next : CompileOutcome.success();
    }

    @Override
    public TestRunOutcome runScopedTests(String testClassSimpleName, List<String> methodNames) {
        calls.add("runScopedTests(" + testClassSimpleName + "#" + String.join("+", methodNames) + ")");
        TestRunOutcome next = scopedRunOutcomes.poll();
        return next != null ? next : new TestRunOutcome(List.of(passing("aGeneratedTest")));
    }

    @Override
    public TestRunOutcome runFullSuite() {
        calls.add("runFullSuite");
        TestRunOutcome next = fullSuiteOutcomes.poll();
        return next != null ? next : new TestRunOutcome(List.of(passing("anExistingTest")));
    }

    @Override
    public Optional<ClassCoverage> measureCoverage(String classFqn) {
        calls.add("measureCoverage(" + classFqn + ")");
        Optional<ClassCoverage> next = coverageOutcomes.poll();
        return next != null ? next : Optional.empty();
    }

    private SurefireTestResult passing(String name) {
        return new SurefireTestResult("com.acme.PaymentServiceTest", name,
                TestOutcome.PASSED, null, null, null, 0.01);
    }

    private SurefireTestResult failing(String name, String message) {
        return new SurefireTestResult("com.acme.PaymentServiceTest", name,
                TestOutcome.ASSERTION_FAILURE, "org.opentest4j.AssertionFailedError", message,
                "at com.acme.PaymentServiceTest." + name, 0.01);
    }
}
