package com.devmanchego.jtestforge.guard;

import java.util.Objects;

/**
 * One guard's reason for discarding a candidate — jtestforge-specification.md §11.1:
 * "Each rejection is recorded with its reason so the report can show <em>why</em> the
 * model's output was thrown away."
 *
 * @param guardId    which guard rejected it, for grouping in the run report
 * @param methodName the candidate test method's name
 * @param reason     a sentence a human can act on, not a code
 */
public record GuardRejection(GuardId guardId, String methodName, String reason) {

    public GuardRejection {
        Objects.requireNonNull(guardId, "guardId");
        Objects.requireNonNull(methodName, "methodName");
        Objects.requireNonNull(reason, "reason");
    }

    @Override
    public String toString() {
        return guardId + " rejected " + methodName + ": " + reason;
    }
}
