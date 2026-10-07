package com.devmanchego.jtestforge.e2e;

import com.devmanchego.jtestforge.config.PromptDelivery;
import com.devmanchego.jtestforge.config.ProviderConfig;
import com.devmanchego.jtestforge.config.ProviderType;
import com.devmanchego.jtestforge.model.WorkUnit;
import com.devmanchego.jtestforge.orchestration.DiscoveredUnit;
import com.devmanchego.jtestforge.orchestration.GenerateResult;
import com.devmanchego.jtestforge.orchestration.TierRestriction;
import com.devmanchego.jtestforge.provider.AiProvider;
import com.devmanchego.jtestforge.provider.AiProviderFactory;
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
     * One entry per provider this manual loop has been run against at least once. Mirrors
     * {@code jtestforge.yaml.template}'s {@code aiProvider.providers} defaults, not a
     * separate source of truth for production - a provider only belongs here once a real
     * pass against it has produced transcripts worth reading. Real {@link ProviderConfig}s,
     * built by the same {@link AiProviderFactory} as the command line, so a profile cannot
     * behave differently here than the same block would in {@code jtestforge.yaml}.
     */
    private static final Map<String, ProviderConfig> KNOWN_PROVIDERS = Map.of(
            "claude", process("claude", List.of("-p", "--output-format", "text"), null, null),
            "gemini", process("gemini.cmd", List.of("-p", ""), null, null),
            "codex", process("codex",
                    List.of("exec", "--skip-git-repo-check", "--sandbox", "read-only", "--color", "never"),
                    null, null),
            "copilot", process("copilot",
                    List.of("--allow-all-tools", "--available-tools", "-s", "--log-level", "error"), null, null),
            // Needs the derived model first: ollama create jtestforge-qwen3-coder -f tools/ollama/Modelfile
            "ollama", process("ollama",
                    List.of("run", "jtestforge-qwen3-coder", "--nowordwrap", "--hidethinking",
                            "--keepalive", "30m", "--verbose"),
                    900, 60000),
            // Ollama's own HTTP API: no derived model needed, the options travel with each request.
            "ollama-http", new ProviderConfig(null, null, null, 900, null, null, 60000,
                    ProviderType.HTTP, "ollama", "http://localhost:11434", "qwen3-coder:30b",
                    Map.of("num_ctx", 32768, "temperature", 0.7, "num_predict", 4096), "30m", null));

    private static ProviderConfig process(String command, List<String> args, Integer timeoutSeconds,
                                          Integer maxPromptChars) {
        return new ProviderConfig(command, args, PromptDelivery.STDIN, timeoutSeconds, null, null, maxPromptChars);
    }

    private TuningLoopRunner() {
    }

    public static void main(String[] args) throws IOException {
        String providerId = System.getProperty("provider", "claude");
        ProviderConfig profile = KNOWN_PROVIDERS.get(providerId);
        if (profile == null) {
            throw new IllegalArgumentException("Unknown -Dprovider=\"" + providerId + "\" - known providers: "
                    + KNOWN_PROVIDERS.keySet() + ". Add an entry to KNOWN_PROVIDERS for a new one.");
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

        AiProvider provider = AiProviderFactory.create(providerId, profile, new ProcessRunner(), workDir);

        int maxPromptChars = maxPromptCharsOverride != null ? maxPromptCharsOverride
                : profile.maxPromptChars() != null ? profile.maxPromptChars() : 60000;

        System.out.println("Running generate against the real \"" + providerId + "\" CLI (this talks to the "
                + "network and can take several minutes)...");
        ContextKeyStabilityTracker tracker = new ContextKeyStabilityTracker(40);
        GenerateResult result = harness.runGenerate(provider, TierRestriction.allTiers(), tracker, 0,
                Duration.ofSeconds(profile.timeoutSeconds()), maxPromptChars);

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
