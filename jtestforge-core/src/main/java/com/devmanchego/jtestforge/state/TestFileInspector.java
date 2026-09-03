package com.devmanchego.jtestforge.state;

import java.nio.file.Path;
import java.util.Set;

/**
 * Reports which {@code @Test} methods a test file currently declares.
 *
 * <p>A port, not an implementation: resume reconciliation needs to ask the filesystem
 * "are the tests this unit claims to have added still there?", but the AST machinery that
 * can answer properly is {@code TestClassScanner}, built in implementation phase 5.
 * Defining the question here keeps {@link ResumeReconciler} testable now with a recorded
 * answer, and lets phase 5 supply the real one without touching the reconciler.
 */
@FunctionalInterface
public interface TestFileInspector {

    /**
     * @param testFile absolute path to a test source file
     * @return names of the {@code @Test} methods it declares; empty if the file is absent
     */
    Set<String> testMethodNames(Path testFile);
}
