package com.devmanchego.jtestforge.model;

import java.util.List;
import java.util.Objects;

/**
 * One {@code @Test} method proposed by the AI, after the response contract parser has
 * extracted it but before any quality gate has judged it — jtestforge-specification.md
 * §6.2.
 *
 * <p>Shape is fixed by the response contract: the model returns new test methods and the
 * imports they need, and nothing else ever reaches the file. This record is therefore
 * exactly what a candidate can be, not a general-purpose container.
 *
 * @param methodName      the method's declared name, used for duplicate detection
 * @param sourceCode      the full method declaration including its annotations
 * @param requiredImports imports the method needs, to be merged into the test class
 */
public record TestCandidate(String methodName, String sourceCode, List<String> requiredImports) {

    public TestCandidate {
        Objects.requireNonNull(methodName, "methodName");
        Objects.requireNonNull(sourceCode, "sourceCode");
        requiredImports = requiredImports == null ? List.of() : List.copyOf(requiredImports);
    }
}
