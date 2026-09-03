package com.devmanchego.jtestforge.prompt;

import com.devmanchego.jtestforge.config.ContextConfig;
import com.devmanchego.jtestforge.model.ClassCoverage;
import com.devmanchego.jtestforge.model.Collaborator;
import com.devmanchego.jtestforge.model.CompilerError;
import com.devmanchego.jtestforge.model.MockBeanDeclaration;
import com.devmanchego.jtestforge.model.ProductionClass;
import com.devmanchego.jtestforge.model.ProductionMethod;
import com.devmanchego.jtestforge.model.ProductionParameter;
import com.devmanchego.jtestforge.model.SemanticGap;
import com.devmanchego.jtestforge.model.SpringStackFacts;
import com.devmanchego.jtestforge.model.SurefireTestResult;
import com.devmanchego.jtestforge.model.TestClassInfo;
import com.devmanchego.jtestforge.model.TestFrameworkVersions;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Formats each piece of a prompt's context — jtestforge-specification.md §6.1.
 *
 * <p>Only formatting lives here, one method per placeholder. Which pieces a given unit
 * needs is the orchestration layer's decision (phases 12-13), so this class never has to
 * know about tiers, passes or work units - and every formatter can be tested on its own
 * against a plain domain object.
 *
 * <p>Every formatter returns a human-readable block, never a serialised structure. The
 * consumer is a language model reading English, so a bulleted list of endpoints beats a
 * JSON dump of the same data - and an empty section says so in words rather than
 * rendering an empty bullet the model has to interpret.
 */
public final class ContextAssembler {

    private static final String NOTHING = "_(none)_";

    private final ContextConfig config;

    public ContextAssembler(ContextConfig config) {
        this.config = Objects.requireNonNull(config, "config");
    }

    // --- plain-tier context ---------------------------------------------------------

    public String classSource(ProductionClass productionClass) {
        if (!config.includeFullProductionClass()) {
            return NOTHING;
        }
        try {
            return Files.readString(productionClass.sourceFile());
        } catch (IOException e) {
            return "_(source unavailable: " + productionClass.sourceFile() + ")_";
        }
    }

    /**
     * The target method's own source, extracted by line range from the class file.
     *
     * <p>Sliced from the real file rather than reprinted from the AST so the model sees
     * the developer's actual formatting and comments - the comments in particular often
     * carry the contract the tests are supposed to encode.
     */
    public String targetMethodSource(ProductionClass productionClass, ProductionMethod method) {
        try {
            List<String> lines = Files.readAllLines(productionClass.sourceFile());
            int from = Math.max(1, method.startLine());
            int to = Math.min(lines.size(), method.endLine());
            if (from > to) {
                return "_(method source unavailable)_";
            }
            return String.join("\n", lines.subList(from - 1, to));
        } catch (IOException e) {
            return "_(method source unavailable)_";
        }
    }

    public String collaborators(ProductionClass productionClass) {
        if (!config.includeCollaboratorSignatures() || productionClass.collaborators().isEmpty()) {
            return NOTHING;
        }
        return productionClass.collaborators().stream()
                .limit(config.maxCollaborators())
                .map(this::describeCollaborator)
                .collect(Collectors.joining("\n"));
    }

    private String describeCollaborator(Collaborator collaborator) {
        return "- `" + collaborator.name() + "` of type `" + collaborator.typeFqn() + "`";
    }

    public String existingTestClass(TestClassInfo testClass) {
        if (testClass == null || !config.includeExistingTestClass()) {
            return NOTHING;
        }
        try {
            return PromptRenderer.capValue(
                    Files.readString(testClass.sourceFile()), config.maxExistingTestChars());
        } catch (IOException e) {
            return "_(test class source unavailable)_";
        }
    }

    public String existingTestNames(TestClassInfo testClass) {
        if (testClass == null || testClass.testMethodNames().isEmpty()) {
            return NOTHING;
        }
        return testClass.testMethodNames().stream().sorted()
                .map(name -> "- `" + name + "`")
                .collect(Collectors.joining("\n"));
    }

    public String frameworkVersions(TestFrameworkVersions versions) {
        if (!config.includeDetectedFrameworkVersions()) {
            return NOTHING;
        }
        List<String> lines = new ArrayList<>();
        addIfPresent(lines, "JUnit Jupiter", versions.junitJupiter());
        addIfPresent(lines, "Mockito", versions.mockito());
        addIfPresent(lines, "AssertJ", versions.assertJ());
        addIfPresent(lines, "Hamcrest", versions.hamcrest());
        lines.add("- `@ExtendWith(MockitoExtension.class)` is "
                + (versions.mockitoJUnitJupiterPresent() ? "available" : "NOT available"));
        lines.add("- static and final mocking (`mockStatic`) is "
                + (versions.mockitoInlinePresent() ? "available" : "NOT available"));
        return String.join("\n", lines);
    }

    private void addIfPresent(List<String> lines, String label, Object version) {
        lines.add("- " + label + ": " + (version == null ? "not on the classpath" : "`" + version + "`"));
    }

    /**
     * Uncovered line numbers with their source text - what {@code additionalTests} is
     * aimed at. The source is included because a bare list of line numbers gives the
     * model nothing to reason about.
     */
    public String uncoveredLines(
            ProductionClass productionClass, ProductionMethod method, ClassCoverage coverage) {
        List<Integer> uncovered = coverage.uncoveredLineNumbers(method.startLine(), method.endLine());
        if (uncovered.isEmpty()) {
            return NOTHING;
        }
        List<String> sourceLines;
        try {
            sourceLines = Files.readAllLines(productionClass.sourceFile());
        } catch (IOException e) {
            return uncovered.stream().map(n -> "- line " + n).collect(Collectors.joining("\n"));
        }
        return uncovered.stream()
                .map(lineNumber -> "- line " + lineNumber + ": `"
                        + (lineNumber <= sourceLines.size() ? sourceLines.get(lineNumber - 1).strip() : "")
                        + "`")
                .collect(Collectors.joining("\n"));
    }

    // --- repair context -------------------------------------------------------------

    public String compilerErrors(List<CompilerError> errors) {
        if (errors.isEmpty()) {
            return NOTHING;
        }
        return errors.stream()
                .map(error -> error.file() + ":" + error.line() + ":" + error.column() + "\n" + error.message())
                .collect(Collectors.joining("\n\n"));
    }

    public String testFailures(List<SurefireTestResult> failures) {
        List<SurefireTestResult> failing = failures.stream().filter(SurefireTestResult::isFailure).toList();
        if (failing.isEmpty()) {
            return NOTHING;
        }
        return failing.stream().map(this::describeFailure).collect(Collectors.joining("\n\n"));
    }

    private String describeFailure(SurefireTestResult failure) {
        StringBuilder text = new StringBuilder(failure.testName())
                .append(" — ").append(failure.outcome());
        if (failure.failureType() != null) {
            text.append("\n").append(failure.failureType());
            if (failure.failureMessage() != null) {
                text.append(": ").append(failure.failureMessage());
            }
        }
        if (failure.stackTraceHead() != null) {
            text.append("\n").append(failure.stackTraceHead());
        }
        return text.toString();
    }

    // --- Spring-tier context --------------------------------------------------------

    /**
     * The exact annotations and helper types this project's Spring version provides —
     * §7.3. Stated as names to use rather than versions to interpret, because the model
     * does not need to know that {@code @MockBean} became {@code @MockitoBean} in 6.2; it
     * needs to know which one compiles here.
     */
    public String springContext(SpringStackFacts facts) {
        List<String> lines = new ArrayList<>();
        lines.add("- Mock a bean with `@" + simpleName(facts.mockBeanAnnotationFqn())
                + "` (import `" + facts.mockBeanAnnotationFqn() + "`)");
        lines.add("- Perform requests with " + (facts.mockMvcTesterAvailable()
                ? "`MockMvcTester` or `MockMvc`" : "`MockMvc` and `MockMvcResultMatchers`"));
        lines.add("- Bean Validation constraints come from `"
                + switch (facts.validationApi()) {
                    case JAKARTA -> "jakarta.validation";
                    case JAVAX -> "javax.validation";
                    case NONE -> "(no Bean Validation API on the classpath)";
                } + "`");
        lines.add("- Spring Security test support is "
                + (facts.securityTestPresent() ? "available (`@WithMockUser` may be used)" : "NOT available"));
        if (facts.hasEmbeddedDatabase()) {
            lines.add("- The embedded database is " + facts.embeddedDatabase());
        }
        return String.join("\n", lines);
    }

    /**
     * The stereotype as plain words, with no markup of its own. Formatting is the
     * template's business - an assembler that wraps its value in backticks produces
     * nested backticks wherever the template already does, which renders as broken
     * markdown in the prompt.
     */
    public String springStereotype(com.devmanchego.jtestforge.model.SpringStereotype stereotype) {
        return stereotype.name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
    }

    public String requestMappings(ProductionClass productionClass) {
        List<String> mappings = new ArrayList<>();
        for (ProductionMethod method : productionClass.methods()) {
            com.devmanchego.jtestforge.spring.RequestMappingDescription.of(productionClass, method)
                    .ifPresent(mappings::add);
        }
        return mappings.isEmpty() ? NOTHING : String.join("\n", mappings);
    }

    /**
     * Only genuine Bean Validation annotations, never the request-binding ones.
     *
     * <p>{@code @PathVariable} and {@code @RequestParam} say how a value <em>arrives</em>,
     * not what makes it valid. Listing them under a "constraints" heading would tell the
     * model that binding annotations are things to write validation-rejection tests for.
     * Binding is already described, correctly, under {@code {{REQUEST_MAPPINGS}}}.
     *
     * <p>Known limitation: constraints declared on the <em>fields of a bound request
     * type</em> - {@code @NotBlank} on a request record's component, say - are not listed
     * here, because {@code ProductionClassScanner} does not scan records and those fields
     * are therefore unavailable. The model does still see them in {@code {{CLASS_SOURCE}}}
     * whenever the type is declared in the same file, which is the common case for a
     * nested request record.
     */
    public String validationConstraints(ProductionClass productionClass) {
        List<String> constraints = new ArrayList<>();
        for (ProductionMethod method : productionClass.methods()) {
            for (ProductionParameter parameter : method.parameters()) {
                List<String> validationAnnotations = parameter.annotations().stream()
                        .filter(this::isValidationAnnotation)
                        .toList();
                if (!validationAnnotations.isEmpty()) {
                    constraints.add("- `" + method.name() + "`, parameter `" + parameter.name()
                            + "` (`" + parameter.typeFqn() + "`) is validated: "
                            + String.join(", ", validationAnnotations));
                }
            }
        }
        return constraints.isEmpty() ? NOTHING : String.join("\n", constraints);
    }

    private boolean isValidationAnnotation(String annotationName) {
        return annotationName.startsWith("jakarta.validation")
                || annotationName.startsWith("javax.validation")
                || annotationName.startsWith("org.springframework.validation");
    }

    public String securityAnnotations(ProductionClass productionClass) {
        List<String> rules = new ArrayList<>();
        for (ProductionMethod method : productionClass.methods()) {
            String securityText = method.annotationAttribute("PreAuthorize");
            if (securityText != null) {
                rules.add("- `" + method.name() + "` requires " + securityText);
            }
        }
        return rules.isEmpty() ? NOTHING : String.join("\n", rules);
    }

    public String exceptionHandlers(ProductionClass productionClass) {
        List<String> handlers = new ArrayList<>();
        for (ProductionMethod method : productionClass.methods()) {
            String handled = method.annotationAttribute("ExceptionHandler");
            if (handled != null) {
                handlers.add("- `" + method.name() + "` handles " + handled);
            }
        }
        return handlers.isEmpty() ? NOTHING : String.join("\n", handlers);
    }

    public String mockBeans(List<MockBeanDeclaration> mockBeans) {
        if (mockBeans.isEmpty()) {
            return NOTHING;
        }
        return mockBeans.stream()
                .map(bean -> "- `" + bean.name() + "` of type `" + bean.typeName() + "`")
                .collect(Collectors.joining("\n"));
    }

    public String persistenceModel(ProductionClass repository) {
        if (repository.methods().isEmpty()) {
            return NOTHING;
        }
        return repository.methods().stream()
                .map(this::describeQueryMethod)
                .collect(Collectors.joining("\n"));
    }

    private String describeQueryMethod(ProductionMethod method) {
        String declaredQuery = method.annotationAttribute("Query");
        return "- `" + method.signature() + "`"
                + (declaredQuery == null ? " (derived from its name)" : " declares " + declaredQuery);
    }

    /**
     * The behavioural statements from §7.5, never the annotations behind them - the same
     * discipline as the mutant translation in §10.2, and for the same reason: instructing
     * at the annotation level produces tests coupled to the implementation.
     */
    public String frameworkSemanticGaps(List<SemanticGap> gaps) {
        if (gaps.isEmpty()) {
            return NOTHING;
        }
        return gaps.stream()
                .map(gap -> "- " + gap.description())
                .collect(Collectors.joining("\n"));
    }

    /** Pass 2's mutant-derived behavioural gaps (§10.2), already translated. */
    public String behaviourGaps(List<String> translatedGaps) {
        if (translatedGaps.isEmpty()) {
            return NOTHING;
        }
        return translatedGaps.stream().map(gap -> "- " + gap).collect(Collectors.joining("\n"));
    }

    private String simpleName(String fqn) {
        int lastDot = fqn.lastIndexOf('.');
        return lastDot < 0 ? fqn : fqn.substring(lastDot + 1);
    }
}
