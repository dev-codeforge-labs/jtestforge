package com.devmanchego.jtestforge.guard;

/**
 * The quality guards of jtestforge-specification.md §11.1, as a closed vocabulary.
 *
 * <p>An enum rather than free text because every rejection is reported: §15's run report
 * shows "tests discarded **with the reason**", and a discard-reason breakdown is only
 * groupable if the reasons are a fixed set. It is also what makes the prompt-effectiveness
 * table meaningful - "this template's output is rejected by MOCK_ONLY 40% of the time" is
 * actionable in a way that a wall of prose is not.
 */
public enum GuardId {

    /** 1. The candidate asserts nothing at all. */
    NO_ASSERTION,
    /** 2. The candidate's only checks are on a mock it just stubbed. */
    MOCK_ONLY,
    /** 3. The candidate asserts a stubbed value came back, with no production logic between. */
    TAUTOLOGY,
    /** 4. The candidate's name collides with an existing test method. */
    DUPLICATE_NAME,
    /** 5. The candidate covers nothing an existing test did not already cover. */
    SEMANTIC_DUPLICATE,
    /** 6. The candidate uses a construct that makes a test slow, flaky or inert. */
    FORBIDDEN_CONSTRUCT,
    /** 7. The candidate lacks the assertion shape the gap it was generated for requires. */
    SEMANTIC_GAP_SHAPE,
    /** 8. A web request whose only expectation is its status code. */
    BARE_STATUS,
    /** 9. The candidate would fork or evict the Spring context cache (§7.6). */
    CONTEXT_KEY,
    /** 10. The candidate turns a slice into an integration test. */
    ENVIRONMENT,
    /** 11. A persistence assertion read back through the first-level cache. */
    PERSISTENCE_HYGIENE
}
