package com.devmanchego.jtestforge.build;

import com.devmanchego.jtestforge.model.CompilerError;

import java.util.List;

/**
 * Result of compiling the module's tests — jtestforge-specification.md §9.4 step 7.
 *
 * @param compiled whether {@code test-compile} succeeded
 * @param errors   javac diagnostics, empty when it did
 */
public record CompileOutcome(boolean compiled, List<CompilerError> errors) {

    public CompileOutcome {
        errors = errors == null ? List.of() : List.copyOf(errors);
    }

    public static CompileOutcome success() {
        return new CompileOutcome(true, List.of());
    }

    public static CompileOutcome failure(List<CompilerError> errors) {
        return new CompileOutcome(false, errors);
    }
}
