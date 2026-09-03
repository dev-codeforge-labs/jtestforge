package com.devmanchego.jtestforge.spring;

import com.devmanchego.jtestforge.model.AssertionShape;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Finds which {@link AssertionShape}s a piece of test code demonstrates.
 *
 * <p>Extracted so the two places that need this question answered give the same answer:
 * {@link GapSuppressionDetector} asks it of an existing test file, to decide a gap is
 * already covered; phase 11's semantic-gap guard asks it of a freshly generated candidate,
 * to decide the candidate actually closes the gap it was generated for. Those are the two
 * ends of the same loop, and a drift between them would mean JTestForge raising a gap,
 * generating a test, accepting it, and then raising the same gap again on the next run.
 */
public final class AssertionEvidenceScanner {

    private static final Set<String> REQUEST_BUILDERS =
            Set.of("get", "post", "put", "delete", "patch", "head", "options", "multipart");
    private static final Set<String> BODY_MATCHERS =
            Set.of("content", "jsonPath", "xpath", "redirectedUrl", "view", "model");
    private static final Set<String> AUTHENTICATION_MARKERS =
            Set.of("user", "withUser", "jwt", "opaqueToken", "authentication");

    /** Every shape demonstrated anywhere under {@code node}. */
    public List<AssertionEvidence> scan(Node node) {
        List<AssertionEvidence> evidence = new ArrayList<>();
        List<WebRequest> webRequests = new ArrayList<>();

        for (MethodCallExpr performCall : node.findAll(MethodCallExpr.class)) {
            if (!performCall.getNameAsString().equals("perform") || performCall.getArguments().isEmpty()) {
                continue;
            }
            requestOf(performCall.getArgument(0)).ifPresent(request -> {
                webRequests.add(request);
                evidence.add(AssertionEvidence.web(
                        AssertionShape.MOCKMVC_REQUEST_TO_PATH, request.httpMethod(), request.path()));
                if (assertsStatusAndBody(performCall)) {
                    // A request whose status and body are both asserted is thorough enough
                    // to close either of the two gaps needing exactly that. Whether the
                    // payload was invalid or a failure was thrown is not decidable
                    // statically, so both shapes are credited.
                    evidence.add(AssertionEvidence.web(
                            AssertionShape.MOCKMVC_INVALID_PAYLOAD_ASSERTS_STATUS_AND_BODY,
                            request.httpMethod(), request.path()));
                    evidence.add(AssertionEvidence.web(
                            AssertionShape.MOCKMVC_ERROR_TRANSLATION, request.httpMethod(), request.path()));
                }
            });
        }
        evidence.addAll(securityEvidence(node, webRequests));
        evidence.addAll(persistenceEvidence(node));
        return List.copyOf(evidence);
    }

    /**
     * A security gap needs a test exercising <em>both</em> outcomes. Asserting only that
     * an authorised caller succeeds proves nothing about the restriction; asserting only
     * the rejection proves nothing about legitimate access still working.
     */
    private List<AssertionEvidence> securityEvidence(Node node, List<WebRequest> webRequests) {
        String source = node.toString();
        boolean hasAuthenticated = node.findAll(MethodCallExpr.class).stream()
                .anyMatch(call -> AUTHENTICATION_MARKERS.contains(call.getNameAsString()))
                || source.contains("@WithMockUser");
        boolean hasAnonymous = node.findAll(MethodCallExpr.class).stream()
                .anyMatch(call -> call.getNameAsString().equals("anonymous"))
                || source.contains("@WithAnonymousUser");

        if (!hasAuthenticated || !hasAnonymous) {
            return List.of();
        }
        return webRequests.stream()
                .map(request -> AssertionEvidence.web(
                        AssertionShape.MOCKMVC_AUTHORIZED_AND_UNAUTHORIZED,
                        request.httpMethod(), request.path()))
                .toList();
    }

    /**
     * A repository query result is only genuinely asserted when the entity manager has
     * been flushed and cleared first - otherwise the read comes back from the first-level
     * cache and the query never ran (§11.1 guard 11).
     */
    private List<AssertionEvidence> persistenceEvidence(Node node) {
        Set<String> calls = node.findAll(MethodCallExpr.class).stream()
                .map(MethodCallExpr::getNameAsString)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        boolean flushed = calls.contains("flush") || calls.contains("persistAndFlush");
        boolean cleared = calls.contains("clear");
        if (flushed && cleared) {
            return List.of(AssertionEvidence.of(AssertionShape.REPOSITORY_QUERY_RESULT_ASSERTED));
        }
        return List.of();
    }

    /**
     * Whether the chain asserts both the response status and something about its body.
     *
     * <p>Only the arguments of {@code andExpect}/{@code andExpectAll} are searched. The
     * request builder's {@code .content("...")} and the result matcher's {@code content()}
     * share a name, so scanning the whole chain reads a request that merely <em>sends</em>
     * a body as one that <em>asserts</em> the response body.
     */
    public boolean assertsStatusAndBody(MethodCallExpr performCall) {
        List<Expression> expectations = new ArrayList<>();
        for (MethodCallExpr call : chainedCallsFrom(performCall)) {
            String name = call.getNameAsString();
            if (name.equals("andExpect") || name.equals("andExpectAll")) {
                expectations.addAll(call.getArguments());
            }
        }
        boolean assertsStatus = expectations.stream()
                .anyMatch(expectation -> containsCallNamed(expectation, Set.of("status")));
        boolean assertsBody = expectations.stream()
                .anyMatch(expectation -> containsCallNamed(expectation, BODY_MATCHERS));
        return assertsStatus && assertsBody;
    }

    /** Every {@code perform(...)} call under {@code node}. */
    public List<MethodCallExpr> performCallsIn(Node node) {
        return node.findAll(MethodCallExpr.class).stream()
                .filter(call -> call.getNameAsString().equals("perform"))
                .toList();
    }

    private boolean containsCallNamed(Expression expression, Set<String> names) {
        if (expression instanceof MethodCallExpr call && names.contains(call.getNameAsString())) {
            return true;
        }
        return expression.findAll(MethodCallExpr.class).stream()
                .anyMatch(call -> names.contains(call.getNameAsString()));
    }

    private List<MethodCallExpr> chainedCallsFrom(MethodCallExpr performCall) {
        Node outermost = performCall;
        while (outermost.getParentNode().isPresent()
                && outermost.getParentNode().get() instanceof MethodCallExpr parentCall) {
            outermost = parentCall;
        }
        return outermost.findAll(MethodCallExpr.class);
    }

    private Optional<WebRequest> requestOf(Expression performArgument) {
        for (MethodCallExpr call : callsIn(performArgument)) {
            if (!REQUEST_BUILDERS.contains(call.getNameAsString()) || call.getArguments().isEmpty()) {
                continue;
            }
            if (call.getArgument(0) instanceof StringLiteralExpr literal) {
                return Optional.of(new WebRequest(
                        call.getNameAsString().toUpperCase(Locale.ROOT), literal.getValue()));
            }
        }
        return Optional.empty();
    }

    private List<MethodCallExpr> callsIn(Expression expression) {
        List<MethodCallExpr> calls = new ArrayList<>();
        if (expression instanceof MethodCallExpr call) {
            calls.add(call);
        }
        calls.addAll(expression.findAll(MethodCallExpr.class));
        return calls;
    }

    /** One MockMvc request observed in test code. */
    record WebRequest(String httpMethod, String path) {
    }
}
