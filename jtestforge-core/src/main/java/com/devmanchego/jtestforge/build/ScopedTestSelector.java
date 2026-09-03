package com.devmanchego.jtestforge.build;

import java.util.List;

/**
 * Builds the {@code -Dtest=} value that scopes a Surefire run to specific methods —
 * jtestforge-specification.md §9.4: {@code mvn test -Dtest=FooTest#newA+newB}.
 *
 * <p>No platform-specific escaping is needed despite the spec calling it out: every
 * process JTestForge launches goes through {@link com.devmanchego.jtestforge.util.ProcessRunner},
 * which always passes an argument list to {@link ProcessBuilder} rather than a shell
 * command line. Neither Windows' {@code CreateProcess} argument parsing nor a POSIX
 * {@code exec} treats {@code #}, {@code +} or {@code ,} as special - those are shell
 * metacharacters, and no shell sits between JTestForge and Maven. This class exists to
 * get the *value* right, once, in one place, rather than to escape it.
 */
public final class ScopedTestSelector {

    private ScopedTestSelector() {
    }

    /**
     * @param testClassSimpleName simple name of the test class, e.g. {@code FooTest}
     * @param methodNames         one or more method names to scope to
     * @return {@code "FooTest#a"} for one method, {@code "FooTest#a+b+c"} for several
     * @throws IllegalArgumentException if {@code methodNames} is empty
     */
    public static String build(String testClassSimpleName, List<String> methodNames) {
        if (methodNames == null || methodNames.isEmpty()) {
            throw new IllegalArgumentException("methodNames must not be empty");
        }
        return testClassSimpleName + "#" + String.join("+", methodNames);
    }

    /** As a ready-to-append Maven system property argument: {@code "-Dtest=FooTest#a+b"}. */
    public static String buildArgument(String testClassSimpleName, List<String> methodNames) {
        return "-Dtest=" + build(testClassSimpleName, methodNames);
    }
}
