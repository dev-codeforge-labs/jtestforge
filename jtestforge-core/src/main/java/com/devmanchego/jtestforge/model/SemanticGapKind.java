package com.devmanchego.jtestforge.model;

/**
 * The nine families of framework-mediated behaviour that a direct method call cannot
 * reach — jtestforge-specification.md §7.5.
 *
 * <p>Each carries the tier able to close it and the {@link AssertionShape} a closing test
 * must have, so the mapping from "signal on the production class" to "what must be
 * asserted" lives in exactly one place.
 */
public enum SemanticGapKind {

    REQUEST_MAPPING(Tier.WEB_SLICE, AssertionShape.MOCKMVC_REQUEST_TO_PATH),
    REQUEST_BINDING(Tier.WEB_SLICE, AssertionShape.MOCKMVC_REQUEST_TO_PATH),
    BEAN_VALIDATION(Tier.WEB_SLICE, AssertionShape.MOCKMVC_INVALID_PAYLOAD_ASSERTS_STATUS_AND_BODY),
    EXCEPTION_TRANSLATION(Tier.WEB_SLICE, AssertionShape.MOCKMVC_ERROR_TRANSLATION),
    METHOD_SECURITY(Tier.WEB_SLICE, AssertionShape.MOCKMVC_AUTHORIZED_AND_UNAUTHORIZED),
    TRANSACTION_ROLLBACK(Tier.DATA_SLICE, AssertionShape.TRANSACTION_ROLLBACK_OBSERVED),
    PROXY_BEHAVIOUR(Tier.CONTEXT_SLICE, AssertionShape.PROXY_BEHAVIOUR_OBSERVED),
    REPOSITORY_QUERY(Tier.DATA_SLICE, AssertionShape.REPOSITORY_QUERY_RESULT_ASSERTED),
    CONFIGURATION_PROPERTIES_BINDING(Tier.JSON_SLICE, AssertionShape.CONFIGURATION_PROPERTIES_BINDING_ASSERTED);

    private final Tier closingTier;
    private final AssertionShape requiredShape;

    SemanticGapKind(Tier closingTier, AssertionShape requiredShape) {
        this.closingTier = closingTier;
        this.requiredShape = requiredShape;
    }

    /** The cheapest tier whose test shape can actually exercise this behaviour. */
    public Tier closingTier() {
        return closingTier;
    }

    public AssertionShape requiredShape() {
        return requiredShape;
    }
}
