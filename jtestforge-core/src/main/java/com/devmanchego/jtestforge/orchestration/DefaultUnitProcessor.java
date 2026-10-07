package com.devmanchego.jtestforge.orchestration;

import com.devmanchego.jtestforge.analysis.MergeResult;
import com.devmanchego.jtestforge.analysis.TestClassMerger;
import com.devmanchego.jtestforge.analysis.TestClassReverter;
import com.devmanchego.jtestforge.analysis.TestClassScanner;
import com.devmanchego.jtestforge.build.CompileOutcome;
import com.devmanchego.jtestforge.build.ModuleBuild;
import com.devmanchego.jtestforge.build.TestRunOutcome;
import com.devmanchego.jtestforge.config.GenerateConfig;
import com.devmanchego.jtestforge.guard.GuardContext;
import com.devmanchego.jtestforge.guard.GuardId;
import com.devmanchego.jtestforge.guard.GuardRejection;
import com.devmanchego.jtestforge.guard.StaticQualityGuards;
import com.devmanchego.jtestforge.model.Collaborator;
import com.devmanchego.jtestforge.model.ContextKey;
import com.devmanchego.jtestforge.model.MockBeanDeclaration;
import com.devmanchego.jtestforge.model.MockField;
import com.devmanchego.jtestforge.model.TestCandidate;
import com.devmanchego.jtestforge.model.TestClassInfo;
import com.devmanchego.jtestforge.model.Tier;
import com.devmanchego.jtestforge.model.UnitStatus;
import com.devmanchego.jtestforge.provider.AiProvider;
import com.devmanchego.jtestforge.provider.ProviderException;
import com.devmanchego.jtestforge.provider.ResponseParseResult;
import com.devmanchego.jtestforge.provider.ResponseParser;
import com.devmanchego.jtestforge.provider.TranscriptWriter;
import com.devmanchego.jtestforge.spring.ContextKeyDecision;
import com.devmanchego.jtestforge.spring.SpringTestClassFactory;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The real per-unit loop of jtestforge-specification.md §9.4, steps 3 to 9, plus the
 * Spring context-key machinery of §7.6.
 *
 * <p>Generate, guard, merge, compile, run, measure, then keep or revert. The write-ahead
 * marker (step 1) and the backup (step 2) belong to {@link GenerateEngine}, which owns the
 * run's state and directories.
 *
 * <p><b>Nothing survives a failure.</b> Every path that does not end in {@code DONE}
 * reverts the file first, by AST subtraction of exactly what this unit added (§9's revert
 * semantics) - never by restoring the backup, which would also discard work earlier units
 * did to the same class. The one deliberate exception is a mock-bean field synthesised
 * during an escalation (§7.6): that is a class-level structural decision, made once, and
 * survives regardless of what happens to the unit that triggered it.
 */
public final class DefaultUnitProcessor implements UnitProcessor {

    private final AiProvider aiProvider;
    private final TranscriptWriter transcriptWriter;
    private final UnitPromptFactory promptFactory;
    private final ResponseParser responseParser;
    private final StaticQualityGuards guards;
    private final TestClassMerger merger;
    private final TestClassReverter reverter;
    private final ModuleBuild moduleBuild;
    private final UnitAcceptanceGate acceptanceGate;
    private final GenerateConfig generateConfig;
    private final Duration providerTimeout;
    private final SpringGenerationSupport springSupport;
    private final java.util.function.Consumer<String> progress;

    private final TestClassScanner testClassScanner;
    private final java.nio.charset.Charset sourceCharset;

    public DefaultUnitProcessor(AiProvider aiProvider, TranscriptWriter transcriptWriter,
                         UnitPromptFactory promptFactory, ResponseParser responseParser,
                         StaticQualityGuards guards, TestClassMerger merger, TestClassReverter reverter,
                         ModuleBuild moduleBuild, UnitAcceptanceGate acceptanceGate,
                         GenerateConfig generateConfig, Duration providerTimeout,
                         SpringGenerationSupport springSupport) {
        this(aiProvider, transcriptWriter, promptFactory, responseParser, guards, merger, reverter,
                moduleBuild, acceptanceGate, generateConfig, providerTimeout, springSupport, null);
    }

    /**
     * @param progress receives one short, content-free line per compile check, per scoped
     *                 test run, and (only when {@code generate.requireCoverageGain} is on)
     *                 per coverage/gap verdict - on by default, so a long-running unit does
     *                 not look stalled. AI-request/response progress is a separate concern,
     *                 already covered by {@link TranscriptWriter}'s own progress sink.
     */
    public DefaultUnitProcessor(AiProvider aiProvider, TranscriptWriter transcriptWriter,
                         UnitPromptFactory promptFactory, ResponseParser responseParser,
                         StaticQualityGuards guards, TestClassMerger merger, TestClassReverter reverter,
                         ModuleBuild moduleBuild, UnitAcceptanceGate acceptanceGate,
                         GenerateConfig generateConfig, Duration providerTimeout,
                         SpringGenerationSupport springSupport, java.util.function.Consumer<String> progress) {
        this(aiProvider, transcriptWriter, promptFactory, responseParser, guards, merger, reverter,
                moduleBuild, acceptanceGate, generateConfig, providerTimeout, springSupport, progress,
                java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * @param sourceCharset the target module's source encoding. The merger and reverter passed in
     *                      must use the same one; it is also what a brand-new test class skeleton
     *                      is written in and what this processor's own test-file scans decode with.
     */
    public DefaultUnitProcessor(AiProvider aiProvider, TranscriptWriter transcriptWriter,
                         UnitPromptFactory promptFactory, ResponseParser responseParser,
                         StaticQualityGuards guards, TestClassMerger merger, TestClassReverter reverter,
                         ModuleBuild moduleBuild, UnitAcceptanceGate acceptanceGate,
                         GenerateConfig generateConfig, Duration providerTimeout,
                         SpringGenerationSupport springSupport, java.util.function.Consumer<String> progress,
                         java.nio.charset.Charset sourceCharset) {
        this.progress = progress;
        this.sourceCharset = Objects.requireNonNull(sourceCharset, "sourceCharset");
        this.testClassScanner = new TestClassScanner(sourceCharset);
        this.aiProvider = Objects.requireNonNull(aiProvider, "aiProvider");
        this.transcriptWriter = Objects.requireNonNull(transcriptWriter, "transcriptWriter");
        this.promptFactory = Objects.requireNonNull(promptFactory, "promptFactory");
        this.responseParser = Objects.requireNonNull(responseParser, "responseParser");
        this.guards = Objects.requireNonNull(guards, "guards");
        this.merger = Objects.requireNonNull(merger, "merger");
        this.reverter = Objects.requireNonNull(reverter, "reverter");
        this.moduleBuild = Objects.requireNonNull(moduleBuild, "moduleBuild");
        this.acceptanceGate = Objects.requireNonNull(acceptanceGate, "acceptanceGate");
        this.generateConfig = Objects.requireNonNull(generateConfig, "generateConfig");
        this.providerTimeout = Objects.requireNonNull(providerTimeout, "providerTimeout");
        this.springSupport = Objects.requireNonNull(springSupport, "springSupport");
    }

    /**
     * What the unit currently being processed has left in the test file - see {@link UnitEdits}.
     * A field rather than a parameter threaded through every private method, because only
     * {@link #process} creates it and only the few places that write to the file update it.
     * Both engines drive a processor one unit at a time, which is what makes this safe; the
     * class was never meant to run two units concurrently (they would share one test file).
     */
    private UnitEdits activeEdits = new UnitEdits();

    /** Where the unit in progress announces its edits before writing them - see {@link UnitEditJournal}. */
    private UnitEditJournal journal = UnitEditJournal.NONE;

    @Override
    public UnitOutcome process(UnitContext originalContext) {
        return process(originalContext, UnitEditJournal.NONE);
    }

    @Override
    public UnitOutcome process(UnitContext originalContext, UnitEditJournal unitJournal) {
        UnitEdits edits = new UnitEdits();
        activeEdits = edits;
        journal = Objects.requireNonNull(unitJournal, "unitJournal");
        try {
            UnitOutcome outcome = processUnit(originalContext);
            if (!outcome.succeeded()) {
                discardSkeletonThisUnitCreated(originalContext.testFile(), edits);
            }
            return outcome;
        } catch (RuntimeException unexpected) {
            // Anything this class did not anticipate - a build runner interrupted, an I/O
            // error while cleaning Surefire reports - must not leave merged tests behind. The
            // engine records the unit as failed, and "nothing survives a failure" has to hold
            // for this exit as it does for every deliberate one.
            rollBack(originalContext.testFile(), edits, unexpected);
            throw unexpected;
        } finally {
            activeEdits = new UnitEdits();
            journal = UnitEditJournal.NONE;
        }
    }

    /**
     * Reverts whatever batch is still merged and discards a skeleton this unit created. A
     * failure of the rollback itself is attached to {@code cause} rather than replacing it:
     * the original exception is the one that explains what went wrong.
     */
    private void rollBack(Path testFile, UnitEdits edits, RuntimeException cause) {
        try {
            if (edits.current != null) {
                reverter.revert(testFile, edits.current.addedTestNames(), edits.current.addedImports());
                edits.current = null;
                journal.record(List.of(), List.of());
            }
            discardSkeletonThisUnitCreated(testFile, edits);
        } catch (RuntimeException rollbackFailure) {
            cause.addSuppressed(rollbackFailure);
        }
    }

    /**
     * A unit that created the test class and ended without keeping a test leaves an empty
     * skeleton behind (found in a real run: an {@code OrderServiceTest} with no tests and an
     * unused import, left in the developer's source tree). It is deleted - but only when it is
     * still exactly that: a class where a mock bean was synthesised is a structural decision
     * that survives by design (see the class comment), and a file that somehow holds test
     * methods or cannot be read is not this unit's to delete.
     */
    private void discardSkeletonThisUnitCreated(Path testFile, UnitEdits edits) {
        if (!edits.createdTestFile || edits.synthesisedMockBeans) {
            return;
        }
        if (testClassScanner.inspect(testFile) instanceof com.devmanchego.jtestforge.analysis.TestFileScan.Parsed parsed
                && parsed.info().testMethodNames().isEmpty()) {
            try {
                java.nio.file.Files.deleteIfExists(testFile);
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException("Failed to remove the empty test class skeleton " + testFile, e);
            }
        }
    }

    private UnitOutcome processUnit(UnitContext originalContext) {
        List<String> discarded = new ArrayList<>();
        // Last line of defence behind WorkUnitDiscovery's SKIPPED_TEST_FILE_UNREADABLE: no
        // test-class info but a file with content means the file could not be read or parsed,
        // not that it is missing - and writing the skeleton would delete every test in it. A
        // file can also turn unreadable mid-run, after discovery looked at it.
        if (!originalContext.testClassExists()
                && testClassScanner.inspect(originalContext.testFile())
                        instanceof com.devmanchego.jtestforge.analysis.TestFileScan.Unreadable unreadable) {
            return UnitOutcome.failed(UnitStatus.SKIPPED_TEST_FILE_UNREADABLE,
                    "the existing test file " + originalContext.testFile().getFileName()
                            + " was left untouched because " + unreadable.reason(), discarded);
        }
        activeEdits.lastAttemptNumber = transcriptEpoch(originalContext);
        UnitContext context = ensureTestFileExists(originalContext);

        MergedCandidates merged;
        try {
            AttemptOutcome firstAttempt = generateAndMergeWithEscalation(
                    context, promptFactory.generationPrompt(context), discarded);
            if (firstAttempt instanceof EscalationFailed failed) {
                return UnitOutcome.failed(UnitStatus.ESCALATION_REQUIRED, failed.reason(), discarded);
            }
            if (!(firstAttempt instanceof MergedCandidates m)) {
                return UnitOutcome.failed(UnitStatus.DISCARDED_NO_VALUE,
                        "no candidate survived the quality guards", discarded);
            }
            merged = m;
        } catch (ProviderException e) {
            return UnitOutcome.failed(UnitStatus.PROVIDER_ERROR, e.getMessage(), discarded);
        }

        try {
            AttemptOutcome compiled = repairUntilCompiling(context, merged, discarded);
            if (compiled instanceof EscalationFailed failed) {
                return UnitOutcome.failed(UnitStatus.ESCALATION_REQUIRED, failed.reason(), discarded);
            }
            if (!(compiled instanceof MergedCandidates compiledMerged)) {
                return UnitOutcome.failed(UnitStatus.FAILED_COMPILE,
                        "the generated tests never compiled", discarded);
            }
            merged = compiledMerged;

            AttemptOutcome passing = repairUntilPassing(context, merged, discarded);
            if (passing instanceof EscalationFailed failed) {
                return UnitOutcome.failed(UnitStatus.ESCALATION_REQUIRED, failed.reason(), discarded);
            }
            if (passing instanceof TestsNeverRan neverRan) {
                return UnitOutcome.failed(UnitStatus.FAILED_ASSERTION, neverRan.reason(), discarded);
            }
            if (!(passing instanceof MergedCandidates passingMerged)) {
                return UnitOutcome.failed(UnitStatus.FAILED_ASSERTION,
                        "the generated tests never passed", discarded);
            }
            merged = passingMerged;

            return applyAcceptanceGate(context, merged, discarded);
        } catch (ProviderException e) {
            revert(context, merged);
            return UnitOutcome.failed(UnitStatus.PROVIDER_ERROR, e.getMessage(), discarded);
        }
    }

    /**
     * Writes the skeleton a brand-new test class starts life as, so the merger always has
     * a real file to splice into. For a Spring tier this is {@link SpringTestClassFactory}'s
     * skeleton, carrying the slice annotation and the class's initial mock-bean set (§7.4);
     * for the plain tier, an empty class with just a package declaration.
     */
    private UnitContext ensureTestFileExists(UnitContext context) {
        if (context.testClassExists()) {
            return context;
        }
        String skeleton = context.unit().tier() == Tier.PLAIN_UNIT
                ? plainSkeletonFor(context)
                : new SpringTestClassFactory(context.springFacts(), springSupport.mockBeanSetResolver())
                        .renderSkeleton(context.productionClass(), context.unit().tier(), context.testClassSimpleName());
        try {
            com.devmanchego.jtestforge.util.AtomicFileWriter.write(context.testFile(), skeleton, sourceCharset);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(
                    "Failed to write new test class skeleton to " + context.testFile(), e);
        }
        activeEdits.createdTestFile = true;
        return context.withTestClassInfo(rescan(context.testFile()));
    }

    /**
     * The skeleton a brand-new plain test class starts from.
     *
     * <p>It imports {@code org.junit.jupiter.api.Test} up front, unconditionally. Every
     * declaration that can ever be merged into this class is a {@code @Test} (or
     * {@code @ParameterizedTest}) method - the response contract enforces exactly that
     * (§6.2 rule 3) - so the import is never left unused, and omitting it would mean the
     * first merge into every newly created class fails to compile for a reason that has
     * nothing to do with what the model wrote. {@link SpringTestClassFactory} does the
     * same for its own skeletons, for the same reason. Found end to end in phase 18.
     */
    private String plainSkeletonFor(UnitContext context) {
        String packageName = context.productionClass().packageName();
        StringBuilder source = new StringBuilder();
        if (!packageName.isEmpty()) {
            source.append("package ").append(packageName).append(";\n\n");
        }
        source.append("import org.junit.jupiter.api.Test;\n\n");
        source.append("class ").append(context.testClassSimpleName()).append(" {\n}\n");
        return source.toString();
    }

    // --- steps 3 to 6, plus the Spring context-key guard (§7.6) -----------------------

    private AttemptOutcome generateAndMerge(
            UnitContext context, String prompt, List<String> discarded)
            throws ProviderException {

        String rawResponse = invoke(context, prompt);
        ResponseParseResult parsed = parse(rawResponse, context);
        if (parsed.isFatal() && !activeEdits.contractCorrectionUsed) {
            // jtestforge-specification.md §6.2: one re-prompt with a corrective message, then
            // the unit is failed. Until this existed, a single misplaced fence ended the unit -
            // which with a local model was 4 units in 5 (jtestforge-ollama-analysis.md, A2).
            activeEdits.contractCorrectionUsed = true;
            discarded.add("response contract violation, asked the model to correct it: "
                    + parsed.violation().message());
            reportProgress("response: unusable format - asking for a corrected answer");
            rawResponse = invoke(context,
                    promptFactory.fixContractPrompt(context, parsed.violation().message(), rawResponse));
            parsed = parse(rawResponse, context);
        }
        parsed.dropped().forEach(drop -> discarded.add(drop.description() + ": " + drop.reason()));
        if (parsed.isFatal()) {
            discarded.add("response contract violation: " + parsed.violation().message());
            return new NoCandidates();
        }

        List<TestCandidate> accepted = applyGuards(parsed.candidates(), context, discarded);
        if (accepted.isEmpty()) {
            return new NoCandidates();
        }

        if (context.unit().tier() != Tier.PLAIN_UNIT) {
            ContextGuardBatchResult contextResult = applyContextKeyGuard(accepted, context, discarded);
            if (contextResult instanceof NeedsEscalation needsEscalation) {
                return new Escalation(testClassFqnOf(context), needsEscalation.missingMockBeans());
            }
            accepted = ((Kept) contextResult).accepted();
            if (accepted.isEmpty()) {
                return new NoCandidates();
            }
        }

        // At most one batch is ever merged at a time (every repair reverts before regenerating),
        // so what is about to be written IS the unit's whole set of edits.
        MergeResult mergeResult = merger.merge(context.testFile(), accepted,
                planned -> journal.record(planned.addedTestNames(), planned.addedImports()));
        if (mergeResult.isRejected()) {
            discarded.add("merge refused: " + mergeResult.rejectionReason());
            return new NoCandidates();
        }
        MergedCandidates merged = new MergedCandidates(accepted, mergeResult.addedTestNames(), mergeResult.addedImports());
        activeEdits.current = merged;
        return merged;
    }

    private String invoke(UnitContext context, String prompt) throws ProviderException {
        return transcriptWriter.invokeAndRecord(aiProvider, prompt, providerTimeout,
                context.unit().id().format(), activeEdits.nextAttemptNumber()).content();
    }

    private ResponseParseResult parse(String rawResponse, UnitContext context) {
        return responseParser.parse(rawResponse,
                context.testClassInfo() == null ? Set.of() : context.testClassInfo().testMethodNames());
    }

    private List<TestCandidate> applyGuards(
            List<TestCandidate> candidates, UnitContext context, List<String> discarded) {
        GuardContext guardContext = new GuardContext(
                context.testClassInfo(), context.targetMethod(), context.unit().tier(), context.gaps());

        List<TestCandidate> accepted = new ArrayList<>();
        for (TestCandidate candidate : candidates) {
            List<GuardRejection> rejections = guards.evaluate(candidate, guardContext);
            if (rejections.isEmpty()) {
                accepted.add(candidate);
            } else {
                rejections.forEach(rejection -> discarded.add(rejection.toString()));
            }
        }
        // Batch-level: needs the whole accepted list, so it cannot be part of the per-candidate pass.
        List<GuardRejection> duplicates = guards.duplicateBodies(accepted, guardContext);
        if (!duplicates.isEmpty()) {
            Set<String> duplicateNames = new LinkedHashSet<>();
            duplicates.forEach(rejection -> {
                duplicateNames.add(rejection.methodName());
                discarded.add(rejection.toString());
            });
            accepted.removeIf(candidate -> duplicateNames.contains(candidate.methodName()));
        }
        return accepted;
    }

    /**
     * Runs every accepted candidate through {@link com.devmanchego.jtestforge.spring.ContextKeyGuard}.
     * A candidate that would fork the context is refused like any other guard rejection.
     * A candidate that needs a mock bean the class does not declare stalls the whole
     * batch: nothing from this attempt is merged, because the class is about to be
     * re-synthesised and every candidate should be re-generated against the new shape
     * rather than half-merged against the old one.
     */
    private ContextGuardBatchResult applyContextKeyGuard(
            List<TestCandidate> candidates, UnitContext context, List<String> discarded) {
        List<TestCandidate> accepted = new ArrayList<>();
        for (TestCandidate candidate : candidates) {
            ContextKeyDecision decision = springSupport.contextKeyGuard()
                    .evaluate(candidate, context.testClassInfo());
            if (decision.isAccepted()) {
                accepted.add(candidate);
                continue;
            }
            if (decision.outcome() == ContextKeyDecision.Outcome.ESCALATION_REQUIRED) {
                List<MockBeanDeclaration> resolved = resolveMissingMockBeans(
                        decision.missingMockBeanTypes(), context);
                if (!resolved.isEmpty()) {
                    return new NeedsEscalation(resolved);
                }
            }
            discarded.add(GuardId.CONTEXT_KEY + " rejected " + candidate.methodName() + ": " + decision.reason());
        }
        return new Kept(accepted);
    }

    /**
     * Turns the names {@link ContextKeyDecision} reports missing into typed mock-bean
     * declarations, by matching them against the production class's own collaborators -
     * the authoritative name-to-type map, since it is exactly where the class's initial
     * mock-bean set was derived from (§7.6, {@code MockBeanSetResolver}). A name that
     * matches no collaborator is not a legitimate escalation - it is the model stubbing
     * something that does not exist - and is left out, which the caller treats as an
     * ordinary rejection when nothing resolves.
     */
    private List<MockBeanDeclaration> resolveMissingMockBeans(List<String> missingNames, UnitContext context) {
        Set<String> alreadyDeclared = new LinkedHashSet<>();
        if (context.testClassInfo() != null) {
            for (MockField field : context.testClassInfo().mockFields()) {
                alreadyDeclared.add(field.name());
            }
        }
        List<MockBeanDeclaration> resolved = new ArrayList<>();
        for (Collaborator collaborator : context.productionClass().collaborators()) {
            if (missingNames.contains(collaborator.name()) && !alreadyDeclared.contains(collaborator.name())) {
                resolved.add(new MockBeanDeclaration(collaborator.name(), collaborator.typeFqn()));
            }
        }
        return resolved;
    }

    /**
     * Resolves an {@link Escalation} into either a resynthesised, retried attempt or a
     * terminal failure — jtestforge-specification.md §7.6's "recomputed... in a single
     * deliberate step". Every direct or repair call to {@link #generateAndMerge} goes
     * through this wrapper, so an escalation surfacing mid-repair is handled the same way
     * as one on the first attempt.
     */
    private AttemptOutcome generateAndMergeWithEscalation(
            UnitContext context, String prompt, List<String> discarded) throws ProviderException {
        AttemptOutcome result = generateAndMerge(context, prompt, discarded);
        if (!(result instanceof Escalation escalation)) {
            return result;
        }
        if (!springSupport.mockBeanEscalation().recordEscalation(escalation.testClassFqn())) {
            return new EscalationFailed(
                    "this test class's mock-bean set was already re-synthesised once this run, and a "
                            + "candidate still needs " + mockBeanFieldNames(escalation.missingMockBeans())
                            + ", which it still does not declare - escalating a second time would fork "
                            + "the context cache key again for a single generated test (§7.6)");
        }
        springSupport.mockBeanSynthesizer().synthesize(
                context.testFile(), escalation.missingMockBeans(), context.springFacts().mockBeanAnnotationFqn());
        activeEdits.synthesisedMockBeans = true;
        UnitContext refreshed = context.withTestClassInfo(rescan(context.testFile()));

        AttemptOutcome retry = generateAndMerge(
                refreshed, promptFactory.generationPrompt(refreshed), discarded);
        if (retry instanceof Escalation) {
            return new EscalationFailed(
                    "even after synthesising " + mockBeanFieldNames(escalation.missingMockBeans())
                            + " onto the test class, the retry still needed a mock bean the class does "
                            + "not declare");
        }
        return retry;
    }

    private String mockBeanFieldNames(List<MockBeanDeclaration> beans) {
        return beans.stream().map(MockBeanDeclaration::name).collect(java.util.stream.Collectors.joining(", "));
    }

    // --- step 7 -----------------------------------------------------------------------

    private AttemptOutcome repairUntilCompiling(
            UnitContext context, MergedCandidates initial, List<String> discarded) throws ProviderException {
        return repairUntilCompiling(context, initial, discarded, "");
    }

    /**
     * @param logPrefix prefix of the build-log label, so a compile-repair round that runs
     *                  <em>after an assertion repair</em> does not overwrite the build log of
     *                  the first batch's own rounds (both would be {@code compile-repair-N})
     */
    private AttemptOutcome repairUntilCompiling(
            UnitContext context, MergedCandidates initial, List<String> discarded,
            String logPrefix) throws ProviderException {
        MergedCandidates current = initial;

        for (int repair = 0; repair <= generateConfig.maxRepairAttempts(); repair++) {
            CompileOutcome outcome = moduleBuild.compileTests();
            reportProgress(outcome.compiled() ? "compile: OK"
                    : "compile: FAILED (" + outcome.errors().size() + " error(s))");
            if (outcome.compiled()) {
                return current;
            }
            String unitId = context.unit().id().format();
            transcriptWriter.recordBuildFailure(unitId, logPrefix + "compile-repair-" + repair, outcome.rawLog());
            if (repair == generateConfig.maxRepairAttempts()) {
                break;
            }
            revert(context, current);
            AttemptOutcome repaired = generateAndMergeWithEscalation(context,
                    promptFactory.fixCompilationPrompt(context, errorsOrRawLogFallback(outcome)),
                    discarded);
            if (repaired instanceof EscalationFailed) {
                return repaired;
            }
            if (!(repaired instanceof MergedCandidates repairedMerged)) {
                return new NoCandidates();
            }
            current = repairedMerged;
        }
        revert(context, current);
        return new NoCandidates();
    }

    /**
     * {@code CompilerErrorParser} only recognises javac's own diagnostic shape; a build
     * that fails for any other reason (an annotation processor, a plugin execution, a
     * dependency it cannot resolve) leaves {@code outcome.errors()} empty even though the
     * build genuinely failed. Sending the model an empty "Compiler errors" section in that
     * case asks it to fix something it cannot see - the one raw-log line the parser missed
     * is exactly what it needs instead.
     */
    private List<com.devmanchego.jtestforge.model.CompilerError> errorsOrRawLogFallback(CompileOutcome outcome) {
        if (!outcome.errors().isEmpty()) {
            return outcome.errors();
        }
        return List.of(new com.devmanchego.jtestforge.model.CompilerError(
                "(build failure not matched by any known javac diagnostic pattern)", 0, 0, outcome.rawLog()));
    }

    // --- step 8 -----------------------------------------------------------------------

    private AttemptOutcome repairUntilPassing(
            UnitContext context, MergedCandidates initial, List<String> discarded) throws ProviderException {
        MergedCandidates current = initial;

        for (int repair = 0; repair <= generateConfig.maxRepairAttempts(); repair++) {
            recordContextLoadIfSpringTier(context);
            TestRunOutcome outcome = moduleBuild.runScopedTests(
                    context.testClassSimpleName(), current.addedTestNames());
            if (outcome.allPassed()) {
                reportProgress("tests: PASSED");
                return current;
            }
            if (outcome.buildFailedBeforeTests()) {
                // The run failed without Surefire reporting a single result, so the tests
                // never actually ran - an old Surefire that does not understand
                // -Dtest=Class#method selection, "No tests were executed", a plugin that
                // blew up. There is no assertion to fix and nothing to show the model:
                // the fix-assertion prompt's "Failures" section would read "(none)",
                // asking it to fix something it cannot see (the same trap
                // errorsOrRawLogFallback avoids on the compile side). Retrying would only
                // reproduce it, so the unit stops here with the build's own words.
                reportProgress("tests: NOT RUN (Surefire reported no result at all)");
                transcriptWriter.recordBuildFailure(context.unit().id().format(),
                        "scoped-run-" + repair, outcome.buildLog());
                revert(context, current);
                return new TestsNeverRan("the scoped test run produced no test result at all: "
                        + outcome.buildFailureSummary().get(0));
            }
            reportProgress("tests: FAILED");
            if (repair == generateConfig.maxRepairAttempts()) {
                break;
            }
            revert(context, current);
            AttemptOutcome repaired = generateAndMergeWithEscalation(context,
                    promptFactory.fixAssertionPrompt(context, outcome.failures()), discarded);
            if (repaired instanceof EscalationFailed) {
                return repaired;
            }
            if (!(repaired instanceof MergedCandidates repairedMerged)) {
                return new NoCandidates();
            }
            current = repairedMerged;
            // A repaired batch has to compile again before it can be re-run: the model may
            // have introduced a fresh compilation error while fixing the assertion - found
            // against a local model that forgot the imports of a matcher it had just started
            // using. That deserves the same compile-repair rounds as the first batch got,
            // not an immediate end to the unit over one missing import. Still bounded:
            // maxRepairAttempts compile repairs per assertion repair.
            AttemptOutcome recompiled = repairUntilCompiling(context, current, discarded,
                    "assertion-repair-" + repair + "-");
            if (recompiled instanceof EscalationFailed) {
                return recompiled;
            }
            if (!(recompiled instanceof MergedCandidates recompiledMerged)) {
                return new NoCandidates();
            }
            current = recompiledMerged;
        }
        revert(context, current);
        return new NoCandidates();
    }

    /**
     * Records the class's current context key against the run's budget — §7.6, §9.6 -
     * right before the Maven invocation that would actually cause Spring to load it. A
     * stable key across repeated calls for the same class costs nothing further (the
     * tracker collapses repeats); only a genuine change - almost always the resynthesis a
     * successful escalation just performed - counts as a second load.
     */
    private void recordContextLoadIfSpringTier(UnitContext context) {
        if (context.unit().tier() == Tier.PLAIN_UNIT) {
            return;
        }
        TestClassInfo current = rescan(context.testFile());
        if (current == null) {
            return;
        }
        ContextKey key = springSupport.contextKeyModel().keyOf(current, context.unit().tier());
        springSupport.contextKeyStabilityTracker().record(fqnOf(current), key);
    }

    // --- step 9 - delegated: coverage/gap for pass 1, mutant-kill for pass 2 ----------

    private UnitOutcome applyAcceptanceGate(UnitContext context, MergedCandidates merged, List<String> discarded) {
        AcceptanceVerdict verdict = acceptanceGate.evaluate(context, merged);
        if (generateConfig.requireCoverageGain()) {
            reportProgress("coverage: " + verdict.reason());
        }
        if (!verdict.keep()) {
            revert(context, merged);
            return UnitOutcome.failed(UnitStatus.DISCARDED_NO_VALUE, verdict.reason(), discarded);
        }
        return UnitOutcome.kept(merged.addedTestNames(), merged.addedImports(), discarded,
                verdict.gapsClosed(), verdict.mutantsKilled(),
                verdict.linesCoveredDelta(), verdict.branchesCoveredDelta());
    }

    private void revert(UnitContext context, MergedCandidates merged) {
        reverter.revert(context.testFile(), merged.addedTestNames(), merged.addedImports());
        if (activeEdits.current == merged) {
            activeEdits.current = null;
        }
        // After the file, not before: a kill in between leaves the journal naming methods that
        // are already gone, and reverting them again is a no-op.
        journal.record(List.of(), List.of());
    }

    /** One short, content-free progress line - see the {@code progress} constructor parameter. */
    private void reportProgress(String message) {
        if (progress != null) {
            progress.accept("  " + message);
        }
    }

    /**
     * A per-call base offset for transcript attempt numbers, so two independent calls to
     * {@link #process} for the <em>same</em> unit id never overwrite each other's
     * transcript files.
     *
     * <p>Pass 1 calls a unit's {@code process()} at most once per run, and
     * {@code context.unit().attempts()} is always {@code 0} then, so this is a no-op there
     * - transcripts number exactly as before. Pass 2's {@code HardenEngine} retries the
     * same unit up to {@code maxAttemptsPerMutant} times within one run, incrementing the
     * unit's {@code attempts} before each retry; multiplying it into the offset keeps every
     * retry's own repair attempts (bounded by {@code maxRepairAttempts}, always small) in
     * a disjoint numbering band, so a failed retry's transcript survives the next one.
     */
    private int transcriptEpoch(UnitContext context) {
        return context.unit().attempts() * 100;
    }

    private TestClassInfo rescan(java.nio.file.Path testFile) {
        return testClassScanner.scan(testFile).orElse(null);
    }

    private String testClassFqnOf(UnitContext context) {
        if (context.testClassInfo() != null) {
            return fqnOf(context.testClassInfo());
        }
        String packageName = context.productionClass().packageName();
        return packageName.isEmpty()
                ? context.testClassSimpleName()
                : packageName + "." + context.testClassSimpleName();
    }

    private String fqnOf(TestClassInfo testClassInfo) {
        return testClassInfo.packageName().isEmpty()
                ? testClassInfo.className()
                : testClassInfo.packageName() + "." + testClassInfo.className();
    }

    // --- outcome types ------------------------------------------------------------------

    /**
     * What the unit in progress has written to the test file and not yet undone: the batch
     * currently merged (null once reverted, or before the first merge), whether this unit
     * created the file, and whether a mock bean was synthesised onto it. Exists so that every
     * way out of {@link #process} - including an exception nobody anticipated - can put the
     * file back, instead of each exit having to remember what to undo.
     */
    private static final class UnitEdits {
        MergedCandidates current;
        boolean createdTestFile;
        boolean synthesisedMockBeans;

        /**
         * Whether this unit has already spent its one corrective re-prompt for a broken response
         * contract. Once per unit, not per answer: it bounds the extra cost at a single call, and a
         * model that cannot follow the format twice is not going to on a third try.
         */
        boolean contractCorrectionUsed;

        /** Attempt number of the last AI call this unit made; see {@link #nextAttemptNumber()}. */
        int lastAttemptNumber;

        /**
         * The number of the next AI call: 1, 2, 3... in the order they happen (offset by the
         * unit's earlier attempts, see {@code transcriptEpoch}). One counter, consumed at the
         * single place that calls the provider, so no two calls of a unit can share a number -
         * which they used to, so a repair's transcript overwrote another's.
         */
        int nextAttemptNumber() {
            return ++lastAttemptNumber;
        }
    }

    /**
     * What one generation attempt produced, before compile/run/value-gate is applied.
     *
     * <p>Not {@code sealed}: {@link MergedCandidates} is a public top-level type (an
     * {@link UnitAcceptanceGate} needs to receive it), and a private nested interface
     * cannot list a type outside this class in its {@code permits} clause. Exhaustiveness
     * is checked by hand at each use site's final {@code else}/fallthrough instead.
     */
    interface AttemptOutcome {
    }

    /**
     * The scoped run failed without producing any Surefire result, so the tests never ran
     * and no assertion can be blamed - distinct from "they ran and failed", which is what
     * {@code FAILED_ASSERTION}'s usual message describes.
     */
    private record TestsNeverRan(String reason) implements AttemptOutcome {
    }

    /** No candidate survived response parsing, the static guards, or the merge. */
    private record NoCandidates() implements AttemptOutcome {
    }

    /**
     * A candidate needs a mock bean the class does not declare. Never returned from
     * {@link #generateAndMergeWithEscalation} - it always resolves an {@code Escalation}
     * into a retried {@code MergedCandidates}/{@code NoCandidates} or an {@code EscalationFailed}.
     */
    private record Escalation(String testClassFqn, List<MockBeanDeclaration> missingMockBeans)
            implements AttemptOutcome {
    }

    /** The class's mock-bean set was already escalated once this run; no further retry. */
    private record EscalationFailed(String reason) implements AttemptOutcome {
    }

    /** Result of running the context-key guard over one batch of accepted candidates. */
    private sealed interface ContextGuardBatchResult permits Kept, NeedsEscalation {
    }

    private record Kept(List<TestCandidate> accepted) implements ContextGuardBatchResult {
    }

    private record NeedsEscalation(List<MockBeanDeclaration> missingMockBeans) implements ContextGuardBatchResult {
    }
}
