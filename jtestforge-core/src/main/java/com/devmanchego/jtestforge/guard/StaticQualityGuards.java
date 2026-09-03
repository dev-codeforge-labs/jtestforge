package com.devmanchego.jtestforge.guard;

import com.devmanchego.jtestforge.config.GenerateConfig;
import com.devmanchego.jtestforge.config.SpringConfig;
import com.devmanchego.jtestforge.model.AssertionShape;
import com.devmanchego.jtestforge.model.MockField;
import com.devmanchego.jtestforge.model.SemanticGap;
import com.devmanchego.jtestforge.model.TestCandidate;
import com.devmanchego.jtestforge.model.Tier;
import com.devmanchego.jtestforge.spring.AssertionEvidence;
import com.devmanchego.jtestforge.spring.AssertionEvidenceScanner;
import com.devmanchego.jtestforge.spring.GapSuppressionDetector;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * The quality gates of jtestforge-specification.md §11.1, applied to a generated candidate
 * <b>before</b> anything is written to disk — the product thesis in code.
 *
 * <p>Rejections here are cheap: no compile, no test run, no coverage measurement. That is
 * the point - a candidate that could never be worth keeping should cost nothing to
 * discard.
 *
 * <p>The calibration this class has to get right is asymmetric, and it is calibrated
 * accordingly. Too lenient and the suite fills with tests that pass and prove nothing; too
 * strict and good generated tests are thrown away, burning invocations and eroding trust
 * in the tool's judgement. <b>Every heuristic below is therefore written to fail towards
 * acceptance</b>: where a construct is ambiguous, it is allowed through, and left for a
 * later gate that can actually measure it (the test still has to compile, pass, and move a
 * metric) rather than guessed at here.
 */
public final class StaticQualityGuards {

    /**
     * Names that count as making an assertion. Deliberately broad: anything beginning
     * {@code assert} catches a project's own custom assertion helpers, which are common
     * and whose rejection would be a particularly annoying false positive.
     */
    private static final Set<String> ASSERTION_CALL_NAMES = Set.of(
            "fail", "verify", "verifyNoInteractions", "verifyNoMoreInteractions", "andExpect",
            "andExpectAll", "then", "shouldHaveNoInteractions", "shouldHaveNoMoreInteractions");
    private static final String ASSERTION_NAME_PREFIX = "assert";

    /** Mockito/BDDMockito entry points that only ever check an interaction. */
    private static final Set<String> INTERACTION_ONLY_CALL_NAMES = Set.of(
            "verify", "verifyNoInteractions", "verifyNoMoreInteractions",
            "shouldHaveNoInteractions", "shouldHaveNoMoreInteractions");

    private static final Set<String> FORBIDDEN_ANNOTATIONS = Set.of("Disabled", "Ignore");
    private static final Set<String> SYSTEM_CLOCK_CALLS = Set.of("now", "currentTimeMillis", "nanoTime");
    private static final Set<String> SYSTEM_CLOCK_SCOPES = Set.of(
            "LocalDate", "LocalDateTime", "LocalTime", "Instant", "ZonedDateTime", "OffsetDateTime",
            "Year", "YearMonth", "System");
    private static final Set<String> LIVE_SERVER_TYPES = Set.of("TestRestTemplate", "WebTestClient");
    private static final Set<String> TESTCONTAINER_TYPES = Set.of(
            "GenericContainer", "PostgreSQLContainer", "MySQLContainer", "MongoDBContainer",
            "KafkaContainer", "DockerComposeContainer");
    /**
     * Filesystem roots that mean a real machine path. A leading {@code /} alone is not
     * enough - {@code "/api/orders"} is a request path, and banning it would reject most
     * legitimate web-slice tests.
     */
    private static final List<String> REAL_FILESYSTEM_PREFIXES = List.of(
            "/home/", "/var/", "/etc/", "/usr/", "/opt/", "/tmp/", "/root/", "/Users/");

    private final GenerateConfig generateConfig;
    private final SpringConfig springConfig;
    private final JavaParser javaParser = new JavaParser(new ParserConfiguration()
            .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21));
    private final AssertionEvidenceScanner evidenceScanner = new AssertionEvidenceScanner();
    private final GapSuppressionDetector gapMatcher = new GapSuppressionDetector();

    public StaticQualityGuards(GenerateConfig generateConfig, SpringConfig springConfig) {
        this.generateConfig = generateConfig;
        this.springConfig = springConfig;
    }

    public List<GuardRejection> evaluate(TestCandidate candidate, GuardContext context) {
        Optional<MethodDeclaration> parsed = parse(candidate);
        if (parsed.isEmpty()) {
            // Not this battery's job to report: the response parser (§6.2) already
            // rejected anything unparseable long before a candidate reaches here.
            return List.of();
        }
        MethodDeclaration method = parsed.get();
        String name = candidate.methodName();
        List<GuardRejection> rejections = new ArrayList<>();

        duplicateName(name, context).ifPresent(rejections::add);
        noAssertion(method, name).ifPresent(rejections::add);
        mockOnly(method, name, context).ifPresent(rejections::add);
        tautology(method, name, context).ifPresent(rejections::add);
        forbiddenConstruct(method, name).ifPresent(rejections::add);

        if (context.isSpringTier()) {
            contextKey(method, name).ifPresent(rejections::add);
            environment(method, name).ifPresent(rejections::add);
            bareStatus(method, name, context).ifPresent(rejections::add);
            persistenceHygiene(method, name, context).ifPresent(rejections::add);
            semanticGapShape(method, name, context).ifPresent(rejections::add);
        }
        return List.copyOf(rejections);
    }

    // --- guard 4 ---------------------------------------------------------------------

    /**
     * Also enforced earlier, by the response parser (§6.2 rule 3) and the merger (§7.2).
     * Repeated here because this battery is what the run report groups discards by, and a
     * duplicate that somehow reached this point should appear in that breakdown rather
     * than being silently dropped upstream with no record.
     */
    private Optional<GuardRejection> duplicateName(String name, GuardContext context) {
        if (context.testClass() == null || !context.testClass().hasTestMethod(name)) {
            return Optional.empty();
        }
        return Optional.of(new GuardRejection(GuardId.DUPLICATE_NAME, name,
                "a test method with this name already exists in the target test class"));
    }

    // --- guard 1 ---------------------------------------------------------------------

    private Optional<GuardRejection> noAssertion(MethodDeclaration method, String name) {
        if (!generateConfig.requireAssertion() || hasAnyAssertion(method)) {
            return Optional.empty();
        }
        return Optional.of(new GuardRejection(GuardId.NO_ASSERTION, name,
                "the test calls the code under test but never checks anything about the result, "
                        + "so it passes for any implementation whatsoever"));
    }

    private boolean hasAnyAssertion(Node method) {
        return method.findAll(MethodCallExpr.class).stream().anyMatch(this::isAssertionCall);
    }

    private boolean isAssertionCall(MethodCallExpr call) {
        String callName = call.getNameAsString();
        return callName.startsWith(ASSERTION_NAME_PREFIX) || ASSERTION_CALL_NAMES.contains(callName);
    }

    // --- guard 2 ---------------------------------------------------------------------

    /**
     * A verify-only test asserts that a mock does what it was just told to do. The one
     * legitimate case is a production method returning {@code void}: there is no value to
     * assert, so the interaction genuinely is the whole contract (§11.1 guard 2's
     * exemption).
     */
    private Optional<GuardRejection> mockOnly(MethodDeclaration method, String name, GuardContext context) {
        if (!generateConfig.rejectMockOnlyTests()) {
            return Optional.empty();
        }
        boolean checksSomething = hasAnyAssertion(method);
        boolean onlyInteractionChecks = checksSomething && method.findAll(MethodCallExpr.class).stream()
                .filter(this::isAssertionCall)
                .allMatch(call -> INTERACTION_ONLY_CALL_NAMES.contains(call.getNameAsString()));
        if (!onlyInteractionChecks) {
            return Optional.empty();
        }
        if (context.targetMethod() != null && context.targetMethod().isVoid()) {
            return Optional.empty();
        }
        return Optional.of(new GuardRejection(GuardId.MOCK_ONLY, name,
                "the test only verifies interactions with a mock, but the method under test returns "
                        + "a value that nothing asserts - it would pass even if that value were wrong"));
    }

    // --- guard 3 ---------------------------------------------------------------------

    /**
     * A tautology asserts a stubbed value came back from the mock itself, with no
     * production code in between. Detected by the assertion's argument being a call
     * <em>on a mock field</em>: a legitimate test asserts on the subject's result, never
     * on the mock's.
     */
    private Optional<GuardRejection> tautology(MethodDeclaration method, String name, GuardContext context) {
        Set<String> mockNames = mockFieldNames(context);
        if (mockNames.isEmpty()) {
            return Optional.empty();
        }
        for (MethodCallExpr assertion : method.findAll(MethodCallExpr.class)) {
            if (!isAssertionCall(assertion) || INTERACTION_ONLY_CALL_NAMES.contains(assertion.getNameAsString())) {
                continue;
            }
            for (Expression argument : assertion.getArguments()) {
                if (isCallOnAMock(argument, mockNames)) {
                    return Optional.of(new GuardRejection(GuardId.TAUTOLOGY, name,
                            "the assertion checks a value returned directly by a mock rather than by the "
                                    + "code under test, so it only confirms the stub was configured"));
                }
            }
        }
        return Optional.empty();
    }

    private boolean isCallOnAMock(Expression expression, Set<String> mockNames) {
        if (!(expression instanceof MethodCallExpr call)) {
            return false;
        }
        return call.getScope()
                .filter(NameExpr.class::isInstance)
                .map(scope -> mockNames.contains(((NameExpr) scope).getNameAsString()))
                .orElse(false);
    }

    private Set<String> mockFieldNames(GuardContext context) {
        if (context.testClass() == null) {
            return Set.of();
        }
        Set<String> names = new LinkedHashSet<>();
        for (MockField field : context.testClass().mockFields()) {
            names.add(field.name());
        }
        return names;
    }

    // --- guard 6 ---------------------------------------------------------------------

    private Optional<GuardRejection> forbiddenConstruct(MethodDeclaration method, String name) {
        for (AnnotationExpr annotation : method.getAnnotations()) {
            String simpleName = simpleNameOf(annotation.getNameAsString());
            if (FORBIDDEN_ANNOTATIONS.contains(simpleName)) {
                return reject(GuardId.FORBIDDEN_CONSTRUCT, name,
                        "the test is annotated @" + simpleName + ", so it would never actually run");
            }
        }
        for (MethodCallExpr call : method.findAll(MethodCallExpr.class)) {
            Optional<String> problem = forbiddenCall(call);
            if (problem.isPresent()) {
                return reject(GuardId.FORBIDDEN_CONSTRUCT, name, problem.get());
            }
        }
        for (ObjectCreationExpr creation : method.findAll(ObjectCreationExpr.class)) {
            if (creation.getType().getNameAsString().equals("Random") && creation.getArguments().isEmpty()) {
                return reject(GuardId.FORBIDDEN_CONSTRUCT, name,
                        "the test uses randomness without a fixed seed, so it does not always test "
                                + "the same thing");
            }
        }
        for (FieldAccessExpr access : method.findAll(FieldAccessExpr.class)) {
            if (isSystemStream(access)) {
                return reject(GuardId.FORBIDDEN_CONSTRUCT, name,
                        "the test writes to System.out/System.err instead of asserting");
            }
        }
        for (StringLiteralExpr literal : method.findAll(StringLiteralExpr.class)) {
            Optional<String> problem = forbiddenLiteral(literal.getValue());
            if (problem.isPresent()) {
                return reject(GuardId.FORBIDDEN_CONSTRUCT, name, problem.get());
            }
        }
        return Optional.empty();
    }

    private Optional<String> forbiddenCall(MethodCallExpr call) {
        String callName = call.getNameAsString();
        String scope = call.getScope().map(Object::toString).orElse("");

        if (callName.equals("sleep") && scope.endsWith("Thread")) {
            return Optional.of("the test sleeps, which makes it slow and flaky rather than deterministic");
        }
        if (callName.equals("random") && scope.endsWith("Math")) {
            return Optional.of("the test uses randomness without a fixed seed, so it does not always "
                    + "test the same thing");
        }
        if (SYSTEM_CLOCK_CALLS.contains(callName) && SYSTEM_CLOCK_SCOPES.contains(simpleNameOf(scope))
                && call.getArguments().isEmpty()) {
            return Optional.of("the test reads the system clock, so it will eventually start failing "
                    + "on its own; inject a fixed Clock instead");
        }
        return Optional.empty();
    }

    private Optional<String> forbiddenLiteral(String value) {
        for (String prefix : REAL_FILESYSTEM_PREFIXES) {
            if (value.startsWith(prefix)) {
                return Optional.of("the test refers to a real filesystem path (" + value + "); "
                        + "use @TempDir instead");
            }
        }
        if (value.matches("^[A-Za-z]:\\\\.*")) {
            return Optional.of("the test refers to a real filesystem path (" + value + "); "
                    + "use @TempDir instead");
        }
        if (isNonLocalUrl(value)) {
            return Optional.of("the test refers to a real network address (" + value + ")");
        }
        return Optional.empty();
    }

    /**
     * A {@code http://localhost/...} literal is not a network call - MockMvc's own
     * {@code redirectedUrl} assertions are written exactly that way, and banning them
     * would reject a whole category of correct web-slice tests.
     */
    private boolean isNonLocalUrl(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            return false;
        }
        return !lower.startsWith("http://localhost") && !lower.startsWith("https://localhost")
                && !lower.startsWith("http://127.0.0.1") && !lower.startsWith("https://127.0.0.1");
    }

    private boolean isSystemStream(FieldAccessExpr access) {
        String field = access.getNameAsString();
        return (field.equals("out") || field.equals("err"))
                && access.getScope().toString().endsWith("System");
    }

    // --- guard 9 (delegated in spirit to phase 6's ContextKeyGuard) -------------------

    /**
     * Mirrors {@code ContextKeyGuard}'s annotation check (§7.6). That class decides the
     * richer three-way outcome (accept / key fork / escalation) during generation; this
     * one exists so a key-forking annotation that somehow reached the merge stage is
     * refused and <em>recorded under a guard id</em> like every other discard.
     */
    private Optional<GuardRejection> contextKey(MethodDeclaration method, String name) {
        if (!springConfig.enforceContextKeyStability()) {
            return Optional.empty();
        }
        for (AnnotationExpr annotation : method.getAnnotations()) {
            String simpleName = simpleNameOf(annotation.getNameAsString());
            if (CONTEXT_KEY_ANNOTATIONS.contains(simpleName)) {
                return reject(GuardId.CONTEXT_KEY, name,
                        "the test carries @" + simpleName + ", which changes this class's Spring "
                                + "context cache key; every test in a class must share one context");
            }
        }
        return Optional.empty();
    }

    private static final Set<String> CONTEXT_KEY_ANNOTATIONS = Set.of(
            "DirtiesContext", "TestPropertySource", "ActiveProfiles", "ContextConfiguration",
            "MockitoBean", "MockBean", "SpyBean", "MockitoSpyBean", "Import", "TestConfiguration",
            "SpringBootTest", "WebMvcTest", "DataJpaTest", "JsonTest", "AutoConfigureTestDatabase");

    // --- guard 10 --------------------------------------------------------------------

    private Optional<GuardRejection> environment(MethodDeclaration method, String name) {
        String source = method.toString();
        if (source.contains("RANDOM_PORT") || source.contains("DEFINED_PORT")) {
            return reject(GuardId.ENVIRONMENT, name,
                    "the test asks for a real servlet container, which turns this slice into an "
                            + "integration test");
        }
        for (String liveServerType : LIVE_SERVER_TYPES) {
            if (usesType(method, liveServerType)) {
                return reject(GuardId.ENVIRONMENT, name,
                        "the test uses " + liveServerType + ", which talks to a running server rather "
                                + "than exercising the slice");
            }
        }
        if (!springConfig.allowTestcontainers()) {
            for (String containerType : TESTCONTAINER_TYPES) {
                if (usesType(method, containerType)) {
                    return reject(GuardId.ENVIRONMENT, name,
                            "the test starts a container, which needs Docker and is disabled by "
                                    + "spring.allowTestcontainers");
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Whether the candidate uses a type, either by naming it or through the conventionally
     * named field that holds it.
     *
     * <p>The field form is the one that actually occurs. A {@code TestRestTemplate} in a
     * real Spring test is an {@code @Autowired} field declared on the class, so a
     * generated method body references only {@code testRestTemplate} - searching the
     * method's text for the type name would miss every genuine instance of the problem
     * this guard exists to catch.
     */
    private boolean usesType(MethodDeclaration method, String typeName) {
        if (method.toString().contains(typeName)) {
            return true;
        }
        String fieldName = decapitalise(typeName);
        return method.findAll(NameExpr.class).stream()
                .anyMatch(nameExpr -> nameExpr.getNameAsString().equals(fieldName));
    }

    private String decapitalise(String name) {
        return name.isEmpty() ? name : Character.toLowerCase(name.charAt(0)) + name.substring(1);
    }

    // --- guard 8 ---------------------------------------------------------------------

    /**
     * The web-slice equivalent of the mock-only test: a request asserting only its status
     * code proves the endpoint is mapped and nothing else. Only applied where the handler
     * actually returns a body - a {@code void} handler has nothing else to check.
     */
    private Optional<GuardRejection> bareStatus(MethodDeclaration method, String name, GuardContext context) {
        if (context.tier() != Tier.WEB_SLICE) {
            return Optional.empty();
        }
        if (context.targetMethod() != null && context.targetMethod().isVoid()) {
            return Optional.empty();
        }
        List<MethodCallExpr> performCalls = evidenceScanner.performCallsIn(method);
        if (performCalls.isEmpty()) {
            return Optional.empty();
        }
        boolean anyAssertsBody = performCalls.stream().anyMatch(evidenceScanner::assertsStatusAndBody);
        if (anyAssertsBody) {
            return Optional.empty();
        }
        return reject(GuardId.BARE_STATUS, name,
                "the request asserts only its status code, which proves the endpoint is mapped and "
                        + "nothing more - assert the response body too");
    }

    // --- guard 11 --------------------------------------------------------------------

    /**
     * Reading an entity back through the same {@code TestEntityManager} without flushing
     * and clearing returns the in-memory instance from the first-level cache: the mapping
     * is never exercised and the test passes regardless of whether it is correct.
     */
    private Optional<GuardRejection> persistenceHygiene(
            MethodDeclaration method, String name, GuardContext context) {
        if (context.tier() != Tier.DATA_SLICE) {
            return Optional.empty();
        }
        Set<String> calls = method.findAll(MethodCallExpr.class).stream()
                .map(MethodCallExpr::getNameAsString)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());

        boolean persists = calls.contains("persist") || calls.contains("persistAndFlush")
                || calls.contains("save") || calls.contains("saveAndFlush");
        if (!persists || !hasAnyAssertion(method)) {
            return Optional.empty();
        }
        boolean flushed = calls.contains("flush") || calls.contains("persistAndFlush")
                || calls.contains("saveAndFlush");
        boolean cleared = calls.contains("clear");
        if (flushed && cleared) {
            return Optional.empty();
        }
        return reject(GuardId.PERSISTENCE_HYGIENE, name,
                "the test reads back what it just persisted without flushing and clearing the entity "
                        + "manager, so the assertion is served from the first-level cache and the "
                        + "mapping is never actually exercised");
    }

    // --- guard 7 ---------------------------------------------------------------------

    /**
     * A candidate generated to close a specific framework-semantic gap must have the
     * assertion shape that gap requires, or it cannot close it - and the gap would be
     * raised again on the very next run.
     */
    private Optional<GuardRejection> semanticGapShape(
            MethodDeclaration method, String name, GuardContext context) {
        if (context.gaps().isEmpty()) {
            return Optional.empty();
        }
        List<AssertionEvidence> evidence = evidenceScanner.scan(method);
        for (SemanticGap gap : context.gaps()) {
            if (evidence.stream().anyMatch(found -> gapMatcher.closes(found, gap))) {
                return Optional.empty();
            }
        }
        SemanticGap first = context.gaps().get(0);
        return reject(GuardId.SEMANTIC_GAP_SHAPE, name,
                "the test does not have the shape needed to close the gap it was generated for ("
                        + describeRequiredShape(first) + ")");
    }

    private String describeRequiredShape(SemanticGap gap) {
        String target = gap.path() == null ? "" : " against " + gap.httpMethod() + " " + gap.path();
        return switch (gap.requiredShape()) {
            case MOCKMVC_REQUEST_TO_PATH -> "it needs a request" + target;
            case MOCKMVC_INVALID_PAYLOAD_ASSERTS_STATUS_AND_BODY ->
                    "it needs a request with an invalid payload asserting both status and body" + target;
            case MOCKMVC_AUTHORIZED_AND_UNAUTHORIZED ->
                    "it needs both an authorised and an unauthorised request" + target;
            case MOCKMVC_ERROR_TRANSLATION ->
                    "it needs a request that triggers the failure, asserting the translated status and body";
            case TRANSACTION_ROLLBACK_OBSERVED -> "it needs an assertion that the transaction rolled back";
            case PROXY_BEHAVIOUR_OBSERVED -> "it needs an assertion that the proxied behaviour happened";
            case REPOSITORY_QUERY_RESULT_ASSERTED ->
                    "it needs to flush and clear, then assert what the query returned";
            case CONFIGURATION_PROPERTIES_BINDING_ASSERTED ->
                    "it needs an assertion that properties bound as declared";
        };
    }

    // --- shared --------------------------------------------------------------------

    private Optional<GuardRejection> reject(GuardId guardId, String methodName, String reason) {
        return Optional.of(new GuardRejection(guardId, methodName, reason));
    }

    private String simpleNameOf(String name) {
        return name.substring(name.lastIndexOf('.') + 1);
    }

    private Optional<MethodDeclaration> parse(TestCandidate candidate) {
        return javaParser.parseBodyDeclaration(candidate.sourceCode()).getResult()
                .filter(BodyDeclaration::isMethodDeclaration)
                .map(BodyDeclaration::asMethodDeclaration);
    }
}
