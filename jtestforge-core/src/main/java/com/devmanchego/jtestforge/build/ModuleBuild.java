package com.devmanchego.jtestforge.build;

import com.devmanchego.jtestforge.model.ClassCoverage;

import java.util.List;
import java.util.Optional;

/**
 * Everything the pass engines need from the target module's build —
 * jtestforge-specification.md §9.4, §13.1.
 *
 * <p>A port expressed in the engine's own vocabulary ("compile the tests", "run these two
 * methods") rather than Maven's ("run these goals with these system properties"). That is
 * deliberate: it keeps goal strings, {@code -Dtest=} escaping and report-file locations
 * inside {@link MavenModuleBuild}, and it means the engine's tests can substitute a fake
 * build that returns scripted outcomes instead of having to imitate Maven's command line -
 * which would test the fake far more than the engine.
 */
public interface ModuleBuild {

    /** {@code mvn test-compile}. */
    CompileOutcome compileTests();

    /**
     * Runs only the named methods of one test class — {@code mvn test -Dtest=Foo#a+b}
     * (§9.4 step 8). Scoped so a unit's own tests are judged without the rest of the
     * module's suite deciding the answer.
     */
    TestRunOutcome runScopedTests(String testClassSimpleName, List<String> methodNames);

    /** The module's whole suite, for §9.5's final verification. */
    TestRunOutcome runFullSuite();

    /**
     * Measures coverage and returns it for one class — {@code mvn jacoco:report} plus a
     * parse of the resulting report. Empty when JaCoCo produced nothing for that class.
     */
    Optional<ClassCoverage> measureCoverage(String classFqn);
}
