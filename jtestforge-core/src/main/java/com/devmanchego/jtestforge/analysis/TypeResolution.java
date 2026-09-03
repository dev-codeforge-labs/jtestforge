package com.devmanchego.jtestforge.analysis;

import com.github.javaparser.ast.type.Type;

/**
 * Resolves a JavaParser {@link Type} node to its fully-qualified name via the configured
 * symbol solver, falling back to the as-written type text on any resolution failure.
 *
 * <p>One unresolvable type (a dependency missing from the classpath, a generic type
 * variable, an unusual construct the solver does not support) must degrade to "best
 * effort" rather than abort scanning the rest of the class or module - jtestforge is
 * reading someone else's codebase, not compiling it, and it needs to keep going even when
 * a corner of the classpath is imperfect.
 */
final class TypeResolution {

    private TypeResolution() {
    }

    static String resolve(Type type) {
        try {
            return type.resolve().describe();
        } catch (RuntimeException e) {
            return type.asString();
        }
    }
}
