package com.devmanchego.jtestforge.spring;

import com.devmanchego.jtestforge.model.MockField;
import com.devmanchego.jtestforge.model.TestCandidate;
import com.devmanchego.jtestforge.model.TestClassInfo;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Decides whether a generated candidate would disturb the test class's context cache key
 * — jtestforge-specification.md §7.6.
 *
 * <p>Two very different situations are separated here, because the engine must react to
 * them differently:
 *
 * <ul>
 *   <li>A candidate that <b>declares</b> a key-changing annotation is simply refused. There
 *       is never a good reason for a generated {@code @Test} method to carry
 *       {@code @DirtiesContext} or {@code @TestPropertySource}: the first evicts the cached
 *       context for every later class sharing that key, and the rest fork a second context
 *       for the sake of one test.</li>
 *   <li>A candidate that <b>needs a mock bean the class does not declare</b> is an
 *       escalation. That is not the model misbehaving - it is the class's mock-bean set
 *       being incomplete, and the fix is to recompute and re-synthesise that set once,
 *       invalidating the cached context exactly one time.</li>
 * </ul>
 */
public final class ContextKeyGuard {

    /** Annotations that alter the context cache key, or evict the cache outright. */
    private static final Set<String> KEY_CHANGING_ANNOTATIONS = Set.of(
            "DirtiesContext", "TestPropertySource", "ActiveProfiles", "ContextConfiguration",
            "MockitoBean", "MockBean", "SpyBean", "MockitoSpyBean", "Import",
            "TestConfiguration", "SpringBootTest", "WebMvcTest", "DataJpaTest", "JsonTest",
            "AutoConfigureTestDatabase", "TestExecutionListeners");

    /** Mockito and BDDMockito entry points whose first argument is the bean being stubbed. */
    private static final Set<String> STUBBING_ENTRY_POINTS =
            Set.of("when", "verify", "given", "then", "doReturn", "doThrow", "doNothing", "doAnswer");

    private final JavaParser javaParser = new JavaParser(new ParserConfiguration()
            .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21));

    public ContextKeyDecision evaluate(TestCandidate candidate, TestClassInfo testClass) {
        Optional<MethodDeclaration> parsed = parse(candidate);
        if (parsed.isEmpty()) {
            return ContextKeyDecision.rejected(
                    "candidate '" + candidate.methodName() + "' is not a parseable method declaration");
        }
        MethodDeclaration method = parsed.get();

        Optional<String> forkingAnnotation = keyChangingAnnotationOn(method);
        if (forkingAnnotation.isPresent()) {
            return ContextKeyDecision.rejected(
                    "candidate '" + candidate.methodName() + "' carries @" + forkingAnnotation.get()
                            + ", which changes this class's Spring context cache key; every test in a "
                            + "class must share one context (see spec 7.6)");
        }

        List<String> missingMockBeans = stubbedNamesNotAvailableIn(method, testClass);
        if (!missingMockBeans.isEmpty()) {
            return ContextKeyDecision.escalation(
                    "candidate '" + candidate.methodName() + "' stubs " + String.join(", ", missingMockBeans)
                            + ", which this test class does not declare as a mock bean; the class's "
                            + "mock-bean set must be recomputed once rather than changed per test",
                    missingMockBeans);
        }
        return ContextKeyDecision.accepted();
    }

    private Optional<String> keyChangingAnnotationOn(MethodDeclaration method) {
        for (AnnotationExpr annotation : method.getAnnotations()) {
            String simpleName = annotation.getNameAsString();
            simpleName = simpleName.substring(simpleName.lastIndexOf('.') + 1);
            if (KEY_CHANGING_ANNOTATIONS.contains(simpleName)) {
                return Optional.of(simpleName);
            }
        }
        return Optional.empty();
    }

    /**
     * Names the candidate stubs that are neither a declared mock field nor something it
     * created for itself.
     *
     * <p>Locals and parameters are excluded, so a candidate that builds its own throwaway
     * mock is not mistaken for one demanding a new bean on the context.
     */
    private List<String> stubbedNamesNotAvailableIn(MethodDeclaration method, TestClassInfo testClass) {
        Set<String> availableNames = new LinkedHashSet<>();
        for (MockField field : testClass.mockFields()) {
            availableNames.add(field.name());
        }
        method.findAll(VariableDeclarationExpr.class).stream()
                .flatMap(declaration -> declaration.getVariables().stream())
                .map(VariableDeclarator::getNameAsString)
                .forEach(availableNames::add);
        method.getParameters().stream().map(Parameter::getNameAsString).forEach(availableNames::add);

        List<String> missing = new ArrayList<>();
        for (String stubbedName : stubbedNamesIn(method)) {
            if (!availableNames.contains(stubbedName) && !missing.contains(stubbedName)) {
                missing.add(stubbedName);
            }
        }
        return missing;
    }

    /** The receiver of each Mockito stubbing or verification call in the candidate. */
    private List<String> stubbedNamesIn(MethodDeclaration method) {
        List<String> names = new ArrayList<>();
        for (MethodCallExpr call : method.findAll(MethodCallExpr.class)) {
            if (!STUBBING_ENTRY_POINTS.contains(call.getNameAsString()) || call.getArguments().isEmpty()) {
                continue;
            }
            receiverNameOf(call.getArgument(0)).ifPresent(names::add);
        }
        return names;
    }

    private Optional<String> receiverNameOf(com.github.javaparser.ast.expr.Expression argument) {
        // when(orderService.findById(1L)) -> the receiver of the inner call
        if (argument instanceof MethodCallExpr innerCall) {
            return innerCall.getScope()
                    .filter(NameExpr.class::isInstance)
                    .map(scope -> ((NameExpr) scope).getNameAsString());
        }
        // verify(orderService) -> the argument itself
        if (argument instanceof NameExpr nameExpr) {
            return Optional.of(nameExpr.getNameAsString());
        }
        return Optional.empty();
    }

    private Optional<MethodDeclaration> parse(TestCandidate candidate) {
        return javaParser.parseBodyDeclaration(candidate.sourceCode()).getResult()
                .filter(BodyDeclaration::isMethodDeclaration)
                .map(BodyDeclaration::asMethodDeclaration);
    }
}
