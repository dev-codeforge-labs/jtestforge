package com.devmanchego.jtestforge.orchestration;

import java.util.List;
import java.util.Objects;

/**
 * Whether a unit's generated tests earned their place —
 * jtestforge-specification.md §9.4 step 9.
 *
 * @param keep        whether the tests stay in the file
 * @param reason      why, in words, for the run log and the unit's recorded outcome
 * @param gapsClosed  ids of the framework-semantic gaps this unit actually closed
 */
public record ValueVerdict(boolean keep, String reason, List<String> gapsClosed) {

    public ValueVerdict {
        Objects.requireNonNull(reason, "reason");
        gapsClosed = gapsClosed == null ? List.of() : List.copyOf(gapsClosed);
    }

    public static ValueVerdict keep(String reason, List<String> gapsClosed) {
        return new ValueVerdict(true, reason, gapsClosed);
    }

    public static ValueVerdict discard(String reason) {
        return new ValueVerdict(false, reason, List.of());
    }
}
