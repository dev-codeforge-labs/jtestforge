package com.devmanchego.jtestforge.model;

/**
 * Which pass produced a run's state — jtestforge-specification.md §3. Recorded in
 * state.json so a resume knows which engine's semantics apply to the stored units, and
 * so a {@code harden} run cannot silently resume a {@code generate} run's state.
 */
public enum Phase {
    GENERATE,
    HARDEN
}
