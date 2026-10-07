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
import java.util.Map;
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

    /**
     * A whole line that is a code fence: three or more backticks, optionally followed by a
     * language. Scanned line by line instead of matched with one regex because a regex pairs
     * fences strictly left to right, so a single stray fence line shifts every block after it
     * - found against a local Ollama model, whose repair answer began with a bare fence line
     * and made the parser see no {@code java} block at all.
     */
    private static final Pattern FENCE_LINE = Pattern.compile("^\\s*(`{3,})\\s*([A-Za-z]*)\\s*$");
    /**
     * A line that only names something to import: {@code import static a.b.C.d;}, {@code
     * a.b.C} or {@code a.b.*}. A block made only of these can never be the block of test
     * methods, so it cannot let anything unwanted through - which is what makes it safe to
     * read as an imports block whatever language label the model gave it. The keyword or at
     * least one dot is required, so a lone identifier line is never mistaken for one.
     */
    private static final Pattern IMPORT_ONLY_LINE = Pattern.compile(
            "^(import\\s+)?(static\\s+)?[A-Za-z_]\\w*(\\.[A-Za-z_]\\w*)*(\\.\\*)?\\s*;?$");
    private static final Pattern UNCLOSED_JAVA_FENCE = Pattern.compile(
            "```java\\r?\\n(?!.*```)", Pattern.DOTALL);
    /**
     * A whole line, once trimmed, shaped like {@code @token\token} - found against a real
     * agentic AI CLI: mid-generation, it narrates an intent to inspect the build (run
     * Maven, check a Surefire report) and that intent leaks into the {@code java} block as
     * a bogus "annotation" naming the report file it meant to look at, one copy pasted
     * ahead of every test method. No legal Java construct is shaped like this - a real
     * annotation's name is a dotted identifier, never containing a raw {@code \} - so a
     * line matching this can only ever be exactly that kind of leaked artifact, never a
     * false positive against real code. Excluding {@code (} and {@code "} is deliberate:
     * it keeps this from ever touching a genuine one-line annotation with an argument.
     */
    private static final Pattern LEAKED_TOOL_NARRATION_LINE = Pattern.compile("^@[^\\s\"()]*\\\\[^\\s\"()]*$");
    private static final Set<String> TEST_ANNOTATIONS = Set.of("Test", "ParameterizedTest");
    /**
     * The one legal import for each recognised test annotation - jtestforge is JUnit 5
     * only (rules.md says so unconditionally), so these are never ambiguous. Found against
     * a real AI CLI whose target project also has JUnit 4 on its classpath (for its own
     * pre-existing, un-migrated tests): it declared {@code org.junit.Test} instead of the
     * JUnit 5 import. The test class already imports the JUnit 5 one - a class only ever
     * has one {@code @Test} in its generation prompt (§6.1) - so nothing is lost by
     * dropping the wrong import; keeping it instead would either fail to resolve (if the
     * legacy JUnit is not a dependency) or collide outright with the existing JUnit 5
     * import (if it is) - both are guaranteed, wasted compile failures.
     */
    private static final Map<String, String> CANONICAL_TEST_ANNOTATION_IMPORTS = Map.of(
            "Test", "org.junit.jupiter.api.Test",
            "ParameterizedTest", "org.junit.jupiter.params.ParameterizedTest");
    private static final Set<String> ALLOWED_WILDCARD_IMPORTS = Set.of(
            "org.mockito.Mockito.*", "org.assertj.core.api.Assertions.*", "org.junit.jupiter.api.Assertions.*");

    private final JavaParser javaParser = new JavaParser(new ParserConfiguration()
            .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21));

    public ResponseParseResult parse(String rawResponse, Set<String> existingTestMethodNames) {
        if (rawResponse == null || rawResponse.isBlank()) {
            return fatal(ContractViolationKind.NOT_FENCED, "the response was empty.");
        }

        String javaBlock = null;
        String firstUnlabelledCode = null;
        boolean sawJavaBlockWithOnlyImports = false;
        List<String> importLines = new ArrayList<>();
        for (FencedBlock block : fencedBlocks(rawResponse)) {
            boolean isJava = block.language().equalsIgnoreCase("java");
            if (block.language().equalsIgnoreCase("imports")) {
                importLines.addAll(block.content().lines().toList());
            } else if ((isJava || block.language().isEmpty()) && isImportOnly(block.content())) {
                // The model put its imports under the wrong label (java, or none at all).
                importLines.addAll(block.content().lines().toList());
                sawJavaBlockWithOnlyImports |= isJava;
            } else if (isJava && javaBlock == null) {
                javaBlock = stripLeakedToolNarrationLines(block.content());
            } else if (block.language().isEmpty() && firstUnlabelledCode == null && !block.content().isBlank()) {
                firstUnlabelledCode = block.content();
            }
        }
        if (javaBlock == null && firstUnlabelledCode != null
                && !UNCLOSED_JAVA_FENCE.matcher(rawResponse).find()) {
            // No ```java block at all, only an unlabelled block of code: the model forgot the
            // label, not the content. Everything downstream still judges that content exactly
            // as if it had been labelled - it must still parse as a list of @Test methods and
            // pass the same guards. An unclosed ```java fence is excluded: that is a truncated
            // answer, and a stray earlier block must not paper over it.
            javaBlock = stripLeakedToolNarrationLines(firstUnlabelledCode);
        }
        if (javaBlock != null) {
            javaBlock = liftLeadingImports(javaBlock, importLines);
        }
        if (javaBlock == null) {
            if (UNCLOSED_JAVA_FENCE.matcher(rawResponse).find()) {
                return fatal(ContractViolationKind.NOT_FENCED,
                        "a ```java block was opened but never closed - the response looks truncated.");
            }
            if (sawJavaBlockWithOnlyImports) {
                return fatal(ContractViolationKind.NOT_FENCED,
                        "the ```java block holds only import statements and no test methods.");
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

        List<String> imports = normalizeImportLines(importLines);
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
            String simpleName = importLine.substring(importLine.lastIndexOf('.') + 1);
            String canonical = CANONICAL_TEST_ANNOTATION_IMPORTS.get(simpleName);
            if (canonical != null && !importLine.equals(canonical)) {
                dropped.add(new DroppedDeclaration(importLine,
                        "not the JUnit 5 import for @" + simpleName + " - the test class already has "
                                + canonical + "; keeping this one would fail to resolve or collide with it"));
                continue;
            }
            kept.add(qualifyBareStaticMemberImport(importLine, simpleName));
        }
        return kept;
    }

    /**
     * Found against a real AI CLI (Codex CLI): asked for a bare import path, it wrote a
     * specific (non-wildcard) static member - {@code org.junit.jupiter.api.Assertions
     * .assertTrue} - without the {@code static} keyword the wildcard branch above already
     * knows to restore. Left alone, {@link com.devmanchego.jtestforge.analysis.TestClassMerger}
     * turns that into a plain {@code import org.junit.jupiter.api.Assertions.assertTrue;},
     * which javac rejects outright: {@code assertTrue} is a method, not a nested type of
     * {@code Assertions}, so a non-static import can never name it.
     *
     * <p>A trailing segment that starts lower-case can only be a method or field - Java
     * type names are always upper-case by convention, and every type this tool ever
     * imports (production classes, DTOs, exceptions, JUnit/Mockito/AssertJ types) follows
     * it. Bare {@code java.math.BigDecimal}-shaped class imports are therefore never
     * touched; only a member reference gets {@code static} restored, exactly as the
     * wildcard branch already restores it for {@code Foo.*}.
     */
    private String qualifyBareStaticMemberImport(String importLine, String simpleName) {
        if (importLine.startsWith("static ") || simpleName.isEmpty() || !Character.isLowerCase(simpleName.charAt(0))) {
            return importLine;
        }
        return "static " + importLine;
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

    private String stripLeakedToolNarrationLines(String javaBlockContent) {
        return javaBlockContent.lines()
                .filter(line -> !LEAKED_TOOL_NARRATION_LINE.matcher(line.strip()).matches())
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    private boolean isTypeDeclaration(BodyDeclaration<?> declaration) {
        return declaration instanceof ClassOrInterfaceDeclaration;
    }

    /** One fenced block of the response; {@code language} is empty when the fence carries none. */
    private record FencedBlock(String language, String content) {
    }

    /**
     * Splits the response into its fenced blocks, in order. Only a block that is properly
     * closed is returned, so a truncated answer still yields no {@code java} block and keeps
     * being reported as truncated.
     *
     * <p>A closing fence never carries a language in Markdown, so a <em>labelled</em> fence
     * met inside an open block can only be the opening of the next one: the block before it
     * was never closed. It is kept if it has content and dropped if it is blank - the blank
     * case being exactly a stray fence line the model left ahead of the real blocks. A
     * closing fence also has to be at least as long as the one that opened the block, which
     * is what lets an answer closed with four backticks (an echo of the prompt's own
     * template) still close a block opened with three.
     */
    private List<FencedBlock> fencedBlocks(String response) {
        List<FencedBlock> blocks = new ArrayList<>();
        String language = null;
        int openLength = 0;
        StringBuilder content = new StringBuilder();
        for (String line : response.split("\\r?\\n", -1)) {
            Matcher fence = FENCE_LINE.matcher(line);
            if (!fence.matches()) {
                if (language != null) {
                    content.append(line).append('\n');
                }
                continue;
            }
            String ticks = fence.group(1);
            String label = fence.group(2);
            if (language == null) {
                language = label;
                openLength = ticks.length();
                content.setLength(0);
            } else if (label.isEmpty() && ticks.length() >= openLength) {
                blocks.add(new FencedBlock(language, content.toString()));
                language = null;
            } else if (!label.isEmpty()) {
                if (!content.toString().isBlank()) {
                    blocks.add(new FencedBlock(language, content.toString()));
                }
                language = label;
                openLength = ticks.length();
                content.setLength(0);
            } else {
                content.append(line).append('\n');
            }
        }
        return blocks;
    }

    /**
     * Moves the {@code import ...;} statements a model wrote at the very top of the tests
     * block into {@code importLines}, and returns the rest. Found against a local Ollama
     * model whose repair answer put its imports and its methods in the same {@code java}
     * block - which the class-body wrapper in {@link #parseAsBodyDeclarations} cannot parse,
     * because an {@code import} cannot legally appear inside a class body.
     *
     * <p>That is what makes this safe: such a block could never have been accepted before, so
     * lifting its leading imports cannot let through anything that used to be refused. Only
     * the unbroken run of imports at the top is lifted (blank lines in between are fine); an
     * import after the first member, a {@code package} line, or a class declaration stops it,
     * so a whole compilation unit is still left intact and still rejected as one.
     */
    private String liftLeadingImports(String javaBlock, List<String> importLines) {
        List<String> lines = javaBlock.lines().toList();
        int firstNonImport = 0;
        List<String> lifted = new ArrayList<>();
        for (String raw : lines) {
            String line = raw.strip();
            if (line.isEmpty()) {
                firstNonImport++;
                continue;
            }
            if (line.startsWith("import ") && line.endsWith(";") && IMPORT_ONLY_LINE.matcher(line).matches()) {
                lifted.add(line);
                firstNonImport++;
                continue;
            }
            break;
        }
        if (lifted.isEmpty()) {
            return javaBlock;
        }
        importLines.addAll(lifted);
        return String.join("\n", lines.subList(firstNonImport, lines.size()));
    }

    /** Whether every non-blank line only names something to import - see {@link #IMPORT_ONLY_LINE}. */
    private boolean isImportOnly(String blockContent) {
        boolean anyLine = false;
        for (String raw : blockContent.lines().toList()) {
            String line = raw.strip();
            if (line.isEmpty()) {
                continue;
            }
            if (!IMPORT_ONLY_LINE.matcher(line).matches()
                    || !(line.startsWith("import ") || line.contains("."))) {
                return false;
            }
            anyLine = true;
        }
        return anyLine;
    }

    /** Blank lines dropped, each line normalised, and a repeated import kept once. */
    private List<String> normalizeImportLines(List<String> rawLines) {
        return rawLines.stream()
                .map(String::strip)
                .filter(line -> !line.isEmpty())
                .map(this::normalizeImportLine)
                .distinct()
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
