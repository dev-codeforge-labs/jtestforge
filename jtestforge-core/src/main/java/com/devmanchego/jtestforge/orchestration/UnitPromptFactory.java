package com.devmanchego.jtestforge.orchestration;

import com.devmanchego.jtestforge.model.CompilerError;
import com.devmanchego.jtestforge.model.SurefireTestResult;
import com.devmanchego.jtestforge.model.Tier;
import com.devmanchego.jtestforge.mutation.MutantBehaviourTranslator;
import com.devmanchego.jtestforge.prompt.ContextAssembler;
import com.devmanchego.jtestforge.prompt.PromptContext;
import com.devmanchego.jtestforge.prompt.PromptPlaceholder;
import com.devmanchego.jtestforge.prompt.PromptRenderer;
import com.devmanchego.jtestforge.prompt.PromptTemplate;
import com.devmanchego.jtestforge.prompt.PromptTemplateId;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Builds the prompt for one attempt at one unit — jtestforge-specification.md §6.1, §9.4.
 *
 * <p>The context is populated for <b>every</b> placeholder, not just the ones the chosen
 * template happens to reference. {@link PromptRenderer} substitutes only what a template
 * actually uses and fails loudly on anything it references but was not given, so
 * over-populating is free and removes an entire class of bug: a template gaining a
 * placeholder would otherwise fail at run time, mid-generation, on a real unit.
 */
public final class UnitPromptFactory {

    private final Map<PromptTemplateId, PromptTemplate> templates;
    private final PromptRenderer renderer;
    private final ContextAssembler assembler;
    private final MutantBehaviourTranslator mutantTranslator = new MutantBehaviourTranslator();

    public UnitPromptFactory(Map<PromptTemplateId, PromptTemplate> templates,
                             PromptRenderer renderer, ContextAssembler assembler) {
        this.templates = Map.copyOf(templates);
        this.renderer = Objects.requireNonNull(renderer, "renderer");
        this.assembler = Objects.requireNonNull(assembler, "assembler");
    }

    public String generationPrompt(UnitContext context) {
        return render(generationTemplateFor(context), context, List.of(), List.of());
    }

    public String fixCompilationPrompt(UnitContext context, List<CompilerError> errors) {
        return render(PromptTemplateId.FIX_COMPILATION, context, errors, List.of());
    }

    public String fixAssertionPrompt(UnitContext context, List<SurefireTestResult> failures) {
        return render(PromptTemplateId.FIX_ASSERTION, context, List.of(), failures);
    }

    /**
     * A pass-2 (mutation) unit always uses {@code kill-mutants.md}, regardless of tier -
     * checked first, since {@link UnitContext#targetMutants()} being non-empty is what
     * actually distinguishes the two passes, not the tier. Otherwise: a Spring tier uses
     * its own slice template; within the plain tier the choice is between creating the
     * first tests for a class and adding to a class that already has some - a materially
     * different instruction, since the second must avoid duplicating what is already there.
     */
    PromptTemplateId generationTemplateFor(UnitContext context) {
        if (context.isMutationUnit()) {
            return PromptTemplateId.KILL_MUTANTS;
        }
        if (context.unit().tier() != Tier.PLAIN_UNIT) {
            return PromptTemplateId.forSpringTier(context.unit().tier()).orElseThrow();
        }
        return context.testClassExists() ? PromptTemplateId.ADDITIONAL_TESTS : PromptTemplateId.NEW_TEST_CLASS;
    }

    private String render(PromptTemplateId templateId, UnitContext context,
                          List<CompilerError> compilerErrors, List<SurefireTestResult> failures) {
        return renderer.render(templates.get(templateId), fullContext(context, compilerErrors, failures));
    }

    private PromptContext fullContext(UnitContext context,
                                      List<CompilerError> compilerErrors, List<SurefireTestResult> failures) {
        var productionClass = context.productionClass();
        var method = context.targetMethod();

        return PromptContext.builder()
                .with(PromptPlaceholder.CLASS_FQN, productionClass.fqn())
                .with(PromptPlaceholder.TARGET_METHOD, method.signature())
                .with(PromptPlaceholder.TARGET_METHOD_SOURCE,
                        assembler.targetMethodSource(productionClass, method))
                .with(PromptPlaceholder.CLASS_SOURCE, assembler.classSource(productionClass))
                .with(PromptPlaceholder.COLLABORATORS, assembler.collaborators(productionClass))
                .with(PromptPlaceholder.EXISTING_TEST_CLASS, assembler.existingTestClass(context.testClassInfo()))
                .with(PromptPlaceholder.EXISTING_TEST_NAMES, assembler.existingTestNames(context.testClassInfo()))
                .with(PromptPlaceholder.FRAMEWORK_VERSIONS, assembler.frameworkVersions(context.frameworkVersions()))
                .with(PromptPlaceholder.UNCOVERED_LINES, uncoveredLines(context))
                .with(PromptPlaceholder.BEHAVIOUR_GAPS,
                        assembler.behaviourGaps(mutantTranslator.translateAll(context.targetMutants())))
                .with(PromptPlaceholder.COMPILER_ERRORS, assembler.compilerErrors(compilerErrors))
                .with(PromptPlaceholder.TEST_FAILURES, assembler.testFailures(failures))
                .with(PromptPlaceholder.TIER, context.unit().tier().name())
                .with(PromptPlaceholder.SPRING_CONTEXT, assembler.springContext(context.springFacts()))
                .with(PromptPlaceholder.SPRING_STEREOTYPE, assembler.springStereotype(context.stereotype()))
                .with(PromptPlaceholder.REQUEST_MAPPINGS, assembler.requestMappings(productionClass))
                .with(PromptPlaceholder.VALIDATION_CONSTRAINTS, assembler.validationConstraints(productionClass))
                .with(PromptPlaceholder.SECURITY_ANNOTATIONS, assembler.securityAnnotations(productionClass))
                .with(PromptPlaceholder.EXCEPTION_HANDLERS, assembler.exceptionHandlers(productionClass))
                .with(PromptPlaceholder.MOCK_BEANS, assembler.mockBeans(context.mockBeans()))
                .with(PromptPlaceholder.PERSISTENCE_MODEL, assembler.persistenceModel(productionClass))
                .with(PromptPlaceholder.FRAMEWORK_SEMANTIC_GAPS, assembler.frameworkSemanticGaps(context.gaps()))
                .with(PromptPlaceholder.RULES, templates.get(PromptTemplateId.RULES).rawText())
                .with(PromptPlaceholder.SPRING_RULES, templates.get(PromptTemplateId.SPRING_RULES).rawText())
                .build();
    }

    private String uncoveredLines(UnitContext context) {
        if (context.coverageBefore() == null) {
            return "_(none)_";
        }
        return assembler.uncoveredLines(context.productionClass(), context.targetMethod(),
                context.coverageBefore());
    }
}
