package com.devmanchego.jtestforge.provider;

import com.devmanchego.jtestforge.model.TestCandidate;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Enforces the response contract — jtestforge-specification.md §6.2. The model is
 * instructed to answer with exactly two fenced blocks and nothing else; this class is
 * what makes that instruction load-bearing rather than trusted.
 *
 * <p>Two outcomes, deliberately kept apart:
 *
 * <ul>
 *   <li>A <b>fatal</b> {@link ContractViolation} - the {@code java} block is missing,
 *       truncated, or shaped as a full compilation unit / class declaration instead of a
 *       list of body declarations. Nothing in the response is usable; this drives the
 *       single corrective re-prompt (§9.4).</li>
 *   <li>A <b>per-declaration drop</b> - a stray field, an inner class, a method missing
 *       {@code @Test}, a duplicate name, a disallowed wildcard import. The response as a
 *       whole is still used; only the offending declaration is discarded, and the drop is
 *       recorded (§6.2 rule 5) rather than silently swallowed.</li>
 * </ul>
 *
 * <p>Every tolerance this class grants is a class of garbage that reaches the test file;
 * every one it withholds is a wasted invocation - the whole reason this phase is built
 * tests-first against an adversarial corpus rather than the other way around.
 */
public final class ResponseParser {

    private static final Pattern FENCE = Pattern.compile(
            "```([A-Za-z]*)\\r?\\n(.*?)```", Pattern.DOTALL);
    private static final Pattern UNCLOSED_JAVA_FENCE = Pattern.compile(
            "```java\\r?\\n(?!.*```)", Pattern.DOTALL);
    private static final Set<String> TEST_ANNOTATIONS = Set.of("Test", "ParameterizedTest");
    private static final Set<String> ALLOWED_WILDCARD_IMPORTS = Set.of(
            "org.mockito.Mockito.*", "org.assertj.core.api.Assertions.*", "org.junit.jupiter.api.Assertions.*");

    private final JavaParser javaParser = new JavaParser(new ParserConfiguration()
            .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21));

    public ResponseParseResult parse(String rawResponse, Set<String> existingTestMethodNames) {
        if (rawResponse == null || rawResponse.isBlank()) {
            return fatal(ContractViolationKind.NOT_FENCED, "the response was empty.");
        }

        String javaBlock = extractFencedBlock(rawResponse, "java");
        if (javaBlock == null) {
            if (UNCLOSED_JAVA_FENCE.matcher(rawResponse).find()) {
                return fatal(ContractViolationKind.NOT_FENCED,
                        "a ```java block was opened but never closed - the response looks truncated.");
            }
            return fatal(ContractViolationKind.NOT_FENCED,
                    "no ```java fenced block was found in the response.");
        }

        List<BodyDeclaration<?>> declarations;
        try {
            declarations = parseAsBodyDeclarations(javaBlock);
        } catch (NotBodyDeclarationsException e) {
            return fatal(ContractViolationKind.JAVA_BLOCK_NOT_BODY_DECLARATIONS, e.getMessage());
        } catch (UnparseableException e) {
            return fatal(ContractViolationKind.UNPARSEABLE_JAVA_BLOCK, e.getMessage());
        }

        List<String> imports = parseImportsBlock(rawResponse);
        List<DroppedDeclaration> dropped = new ArrayList<>();
        List<String> keptImports = filterImports(imports, dropped);

        Set<String> seenNames = new LinkedHashSet<>(existingTestMethodNames);
        List<TestCandidate> candidates = new ArrayList<>();
        for (BodyDeclaration<?> declaration : declarations) {
            classify(declaration, seenNames, keptImports, candidates, dropped);
        }

        return ResponseParseResult.usable(candidates, dropped);
    }

    private void classify(
            BodyDeclaration<?> declaration, Set<String> seenNames, List<String> keptImports,
            List<TestCandidate> candidates, List<DroppedDeclaration> dropped) {
        if (!declaration.isMethodDeclaration()) {
            dropped.add(new DroppedDeclaration(describe(declaration), "not a test method declaration"));
            return;
        }
        MethodDeclaration method = declaration.asMethodDeclaration();
        String name = method.getNameAsString();

        if (!hasAnyAnnotation(method, TEST_ANNOTATIONS)) {
            dropped.add(new DroppedDeclaration(name,
                    "missing @Test or @ParameterizedTest - not recognisable as a test method"));
            return;
        }
        if (seenNames.contains(name)) {
            dropped.add(new DroppedDeclaration(name,
                    "duplicates a test method name already present in this test class or earlier in this response"));
            return;
        }

        seenNames.add(name);
        candidates.add(new TestCandidate(name, method.toString(), keptImports));
    }

    private String describe(BodyDeclaration<?> declaration) {
        if (declaration.isFieldDeclaration()) {
            FieldDeclaration field = declaration.asFieldDeclaration();
            String names = field.getVariables().stream()
                    .map(v -> v.getNameAsString())
                    .reduce((a, b) -> a + ", " + b).orElse("?");
            return "a field named " + names;
        }
        if (declaration.isClassOrInterfaceDeclaration()) {
            return "a nested type named " + declaration.asClassOrInterfaceDeclaration().getNameAsString();
        }
        return declaration.getClass().getSimpleName();
    }

    private boolean hasAnyAnnotation(MethodDeclaration method, Set<String> simpleNames) {
        for (AnnotationExpr annotation : method.getAnnotations()) {
            String simpleName = annotation.getNameAsString();
            simpleName = simpleName.substring(simpleName.lastIndexOf('.') + 1);
            if (simpleNames.contains(simpleName)) {
                return true;
            }
        }
        return false;
    }

    /**
     * All three entries of {@link #ALLOWED_WILDCARD_IMPORTS} exist to bring in a class's
     * static members unqualified ({@code assertTrue}, {@code mock}, {@code verify}...) - a
     * plain (non-static) wildcard import of any of them would compile but leave every
     * unqualified call in the response unresolved. Found against a real AI CLI: the model
     * sometimes drops the {@code static} keyword the prompt asked for, and that bare form
     * happens to match the allowed set literally - so it was kept exactly as the model
     * wrote it, silently producing a broken import. Comparing (and re-adding) the
     * {@code static} prefix here, rather than trusting whichever form the model chose,
     * means the kept import is always the one that actually compiles.
     */
    private List<String> filterImports(List<String> imports, List<DroppedDeclaration> dropped) {
        List<String> kept = new ArrayList<>();
        for (String importLine : imports) {
            if (importLine.endsWith(".*")) {
                String withoutStaticPrefix = importLine.startsWith("static ")
                        ? importLine.substring("static ".length()) : importLine;
                if (!ALLOWED_WILDCARD_IMPORTS.contains(withoutStaticPrefix)) {
                    dropped.add(new DroppedDeclaration(importLine,
                            "wildcard import is not one of the allowed static-import idioms"));
                    continue;
                }
                kept.add("static " + withoutStaticPrefix);
                continue;
            }
            kept.add(importLine);
        }
        return kept;
    }

    /**
     * Parses {@code javaBlockContent} as a list of body declarations, by wrapping it in a
     * synthetic class body. This is what lets an ordinary batch of {@code @Test} methods
     * parse the same way a class's own members would, while still detecting the two rule-2
     * violation shapes precisely:
     *
     * <ul>
     *   <li>The wrapped content parses, but the synthetic class ends up with exactly one
     *       member and that member is itself a type declaration - the model wrapped
     *       everything in one class.</li>
     *   <li>The wrapped content does not parse at all (a package declaration or top-level
     *       import cannot legally appear inside a class body) - retried as a raw
     *       compilation unit; if that succeeds, the model emitted a full file.</li>
     * </ul>
     */
    private List<BodyDeclaration<?>> parseAsBodyDeclarations(String javaBlockContent) {
        ParseResult<CompilationUnit> wrapped = javaParser.parse(
                "class __JTestForgeResponseWrapper__ { " + javaBlockContent + " }");

        if (wrapped.isSuccessful() && wrapped.getResult().isPresent()) {
            List<TypeDeclaration<?>> types = wrapped.getResult().get().getTypes();
            List<BodyDeclaration<?>> members = types.get(0).getMembers();
            if (members.size() == 1 && isTypeDeclaration(members.get(0))) {
                throw new NotBodyDeclarationsException(
                        "the java block contains a single class/interface declaration instead of "
                                + "a list of test methods.");
            }
            return List.copyOf(members);
        }

        ParseResult<CompilationUnit> asFullUnit = javaParser.parse(javaBlockContent);
        if (asFullUnit.isSuccessful() && asFullUnit.getResult().isPresent()
                && !asFullUnit.getResult().get().getTypes().isEmpty()) {
            throw new NotBodyDeclarationsException(
                    "the java block is a full compilation unit (with its own package/import "
                            + "statements) instead of a list of test methods.");
        }

        throw new UnparseableException("the java block's content is not valid Java: "
                + wrapped.getProblems().stream().findFirst().map(Object::toString).orElse("unknown syntax error"));
    }

    private boolean isTypeDeclaration(BodyDeclaration<?> declaration) {
        return declaration instanceof ClassOrInterfaceDeclaration;
    }

    private String extractFencedBlock(String response, String language) {
        Matcher matcher = FENCE.matcher(response);
        while (matcher.find()) {
            if (matcher.group(1).equalsIgnoreCase(language)) {
                return matcher.group(2);
            }
        }
        return null;
    }

    private List<String> parseImportsBlock(String response) {
        String block = extractFencedBlock(response, "imports");
        if (block == null || block.isBlank()) {
            return List.of();
        }
        return block.lines()
                .map(String::strip)
                .filter(line -> !line.isEmpty())
                .map(this::normalizeImportLine)
                .toList();
    }

    /**
     * The prompt asks for a bare import path (no {@code import} keyword, no trailing
     * {@code ;} - {@link com.devmanchego.jtestforge.analysis.TestClassMerger} adds both
     * itself), but a real model sometimes writes the full statement anyway. Left alone,
     * that both slips past {@link #filterImports}'s wildcard check (which looks for a
     * trailing {@code .*}, not {@code .*;}) and, once merged, produces a doubled
     * {@code import import ...;;} line that no longer parses. Stripping the wrapping here,
     * once, keeps every downstream consumer working from the same bare form regardless of
     * which style the model chose.
     */
    private String normalizeImportLine(String line) {
        String normalized = line;
        if (normalized.startsWith("import ")) {
            normalized = normalized.substring("import ".length()).strip();
        }
        if (normalized.endsWith(";")) {
            normalized = normalized.substring(0, normalized.length() - 1).strip();
        }
        return normalized;
    }

    private ResponseParseResult fatal(ContractViolationKind kind, String message) {
        return ResponseParseResult.fatal(new ContractViolation(kind, message));
    }

    private static final class NotBodyDeclarationsException extends RuntimeException {
        NotBodyDeclarationsException(String message) {
            super(message);
        }
    }

    private static final class UnparseableException extends RuntimeException {
        UnparseableException(String message) {
            super(message);
        }
    }
}
