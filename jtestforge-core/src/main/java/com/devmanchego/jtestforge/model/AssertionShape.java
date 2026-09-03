package com.devmanchego.jtestforge.model;

/**
 * The structural form a test must take to close a given {@link SemanticGap}.
 *
 * <p>This is the fingerprint that makes framework-semantic gaps mechanically checkable at
 * both ends of the loop: {@code GapSuppressionDetector} uses it to decide a gap is
 * <em>already</em> covered by a hand-written test, and phase 11's guard 7 uses it to
 * reject a generated candidate that does not actually have the shape its gap requires.
 *
 * <p>Matching on shape rather than on test-method names is deliberate. A name is a
 * convention the target project may not follow, and a generated test could satisfy any
 * naming rule while asserting nothing relevant.
 */
public enum AssertionShape {

    /** A MockMvc/MockMvcTester request against a specific HTTP method and path. */
    MOCKMVC_REQUEST_TO_PATH,

    /** A request with an invalid payload, asserting both the status and the response body. */
    MOCKMVC_INVALID_PAYLOAD_ASSERTS_STATUS_AND_BODY,

    /** Both an authorised and an unauthorised request against the same endpoint. */
    MOCKMVC_AUTHORIZED_AND_UNAUTHORIZED,

    /** A request that triggers the handled exception, asserting the translated status and body. */
    MOCKMVC_ERROR_TRANSLATION,

    /** An assertion that the transaction rolled back for a declared rollback exception type. */
    TRANSACTION_ROLLBACK_OBSERVED,

    /**
     * An assertion that the proxy-mediated behaviour happened - a second call served from
     * cache, an async completion, a retry, an event delivered.
     */
    PROXY_BEHAVIOUR_OBSERVED,

    /** An assertion on what a repository query actually returned, run against a real database. */
    REPOSITORY_QUERY_RESULT_ASSERTED,

    /** An assertion that properties bound as declared, including defaults and validation failure. */
    CONFIGURATION_PROPERTIES_BINDING_ASSERTED
}
