package com.devmanchego.jtestforge.e2e;

import com.devmanchego.jtestforge.config.PromptDelivery;
import com.devmanchego.jtestforge.model.WorkUnit;
import com.devmanchego.jtestforge.orchestration.DiscoveredUnit;
import com.devmanchego.jtestforge.orchestration.GenerateResult;
import com.devmanchego.jtestforge.orchestration.TierRestriction;
import com.devmanchego.jtestforge.provider.AiProvider;
import com.devmanchego.jtestforge.provider.ProcessAiProvider;
import com.devmanchego.jtestforge.spring.ContextKeyStabilityTracker;
import com.devmanchego.jtestforge.util.ProcessRunner;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Manual driver for the post-phase-18 tuning loop (jtestforge-implementation-plan.md,
 * "After phase 18 — the tuning loop"). Not a test: it spends real invocations of a real
 * AI CLI and is meant to be run and read by a person, one pass at a time.
 *
 * <p>Run with (defaults to {@code claude}):
 * <pre>
 * mvn -o test-compile org.codehaus.mojo:exec-maven-plugin:3.1.0:java \
 *     -Dexec.mainClass=com.devmanchego.jtestforge.e2e.TuningLoopRunner \
 *     -Dexec.classpathScope=test
 * </pre>
 *
 * <p>Pass {@code -Dprovider=<name>} (see {@link #KNOWN_PROVIDERS}) to point the same pass
 * at a different CLI — this is how a new {@code AiProvider} entry earns its place in
 * {@code jtestforge.yaml.template} (§5): run this once per candidate provider, read the
 * transcripts, and see whether {@link com.devmanchego.jtestforge.provider.ResponseParser}
 * needs a new tolerance before that provider's response contract can be trusted. Override
 * an individual provider's context budget with {@code -DmaxPromptChars=N} when its CLI's
 * window is known to differ from {@link ProviderProfile#maxPromptChars} (a locally hosted
 * model is the usual reason).
 *
 * <p>Each run copies the checked-in fixture to a fresh temp directory (never mutates
 * {@code src/test/resources/spring-fixture-module}), runs a real {@code generate} against
 * it with the chosen AI CLI, and prints a per-unit report — status, kept/discarded test
 * names, and the last error for anything that did not survive — plus the on-disk location
 * of every prompt/response transcript for later reading.
 */
public final class TuningLoopRunner {

    /**
     * One entry per CLI this manual loop has been run against at least once. Mirrors
     * {@code jtestforge.yaml.template}'s {@code aiProvider.providers} defaults, not a
     * separate source of truth for production - a provider only belongs here once a real
     * pass against it has produced transcripts worth reading.
     */
    private record ProviderProfile(
            String command, List<String> args, PromptDelivery promptDelivery, Integer maxPromptChars) {
    }

    private static final Map<String, ProviderProfile> KNOWN_PROVIDERS = Map.of(
            "claude", new ProviderProfile("claude",
                    List.of("-p", "--output-format", "text"), PromptDelivery.STDIN, null),
            "gemini", new ProviderProfile("gemini.cmd",
                    List.of("-p", ""), PromptDelivery.STDIN, null),
            "codex", new ProviderProfile("codex",
                    List.of("exec", "--skip-git-repo-check", "--sandbox", "read-only", "--color", "never"),
                    PromptDelivery.STDIN, null),
            "copilot", new ProviderProfile("copilot",
                    List.of("--allow-all-tools", "--available-tools", "-s", "--log-level", "error"),
                    PromptDelivery.STDIN, null));

    private TuningLoopRunner() {
    }

    public static void main(String[] args) throws IOException {
        String providerId = System.getProperty("provider", "claude");
        ProviderProfile profile = KNOWN_PROVIDERS.get(providerId);
        if (profile == null) {
            throw new IllegalArgumentException("Unknown -Dprovider=\"" + providerId + "\" - known providers: "
                    + KNOWN_PROVIDERS.keySet() + ". Add a ProviderProfile entry for a new one.");
        }
        Integer maxPromptCharsOverride = Integer.getInteger("maxPromptChars");

        Path workDir = Files.createTempDirectory("jtestforge-tuning-");
        System.out.println("Working copy: " + workDir);
        FixtureModuleHarness.copyFixtureTo(workDir);

        FixtureModuleHarness harness = new FixtureModuleHarness(workDir);
        System.out.println("Building fixture module and measuring baseline coverage...");
        harness.preflightAndBaseline();

        List<DiscoveredUnit> discovered = harness.discoverUnits();
        System.out.println("Discovered " + discovered.size() + " work unit(s):");
        for (DiscoveredUnit unit : discovered) {
            System.out.println("  " + unit.workUnit().id().format());
        }

        AiProvider provider = new ProcessAiProvider(
                providerId, new ProcessRunner(), profile.command(),
                profile.args(), profile.promptDelivery(), 2, workDir);

        int maxPromptChars = maxPromptCharsOverride != null ? maxPromptCharsOverride
                : profile.maxPromptChars() != null ? profile.maxPromptChars() : 60000;

        System.out.println("Running generate against the real \"" + providerId + "\" CLI (this talks to the "
                + "network and can take several minutes)...");
        ContextKeyStabilityTracker tracker = new ContextKeyStabilityTracker(40);
        GenerateResult result = harness.runGenerate(provider, TierRestriction.allTiers(), tracker, 0,
                Duration.ofSeconds(300), maxPromptChars);

        System.out.println();
        System.out.println("=== Result ===");
        System.out.println("Exit reason: " + result.exitReason());
        for (WorkUnit unit : result.state().units()) {
            System.out.println();
            System.out.println(unit.id().format());
            System.out.println("  status: " + unit.status());
            if (unit.lastError() != null) {
                System.out.println("  lastError: " + unit.lastError());
            }
            if (!unit.discardedTests().isEmpty()) {
                System.out.println("  discardedTests:");
                unit.discardedTests().forEach(t -> System.out.println("    - " + t));
            }
        }

        Path transcripts = workDir.resolve(".jtestforge").resolve("transcripts");
        System.out.println();
        System.out.println("Transcripts: " + transcripts);
        System.out.println("Working copy left in place for inspection: " + workDir);
    }
}
