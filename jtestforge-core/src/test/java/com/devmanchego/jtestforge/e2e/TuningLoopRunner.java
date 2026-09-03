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
import java.util.List;

/**
 * Manual driver for the post-phase-18 tuning loop (jtestforge-implementation-plan.md,
 * "After phase 18 — the tuning loop"). Not a test: it spends real invocations of a real
 * AI CLI and is meant to be run and read by a person, one pass at a time.
 *
 * <p>Run with:
 * <pre>
 * mvn -o test-compile org.codehaus.mojo:exec-maven-plugin:3.1.0:java \
 *     -Dexec.mainClass=com.devmanchego.jtestforge.e2e.TuningLoopRunner \
 *     -Dexec.classpathScope=test
 * </pre>
 *
 * <p>Each run copies the checked-in fixture to a fresh temp directory (never mutates
 * {@code src/test/resources/spring-fixture-module}), runs a real {@code generate} against
 * it with the real {@code claude} CLI, and prints a per-unit report — status, kept/discarded
 * test names, and the last error for anything that did not survive — plus the on-disk
 * location of every prompt/response transcript for later reading.
 */
public final class TuningLoopRunner {

    private TuningLoopRunner() {
    }

    public static void main(String[] args) throws IOException {
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

        AiProvider claude = new ProcessAiProvider(
                "claude", new ProcessRunner(), "claude",
                List.of("-p", "--output-format", "text"),
                PromptDelivery.STDIN, 2, workDir);

        System.out.println("Running generate against the real claude CLI (this talks to the network "
                + "and can take several minutes)...");
        ContextKeyStabilityTracker tracker = new ContextKeyStabilityTracker(40);
        GenerateResult result = harness.runGenerate(claude, TierRestriction.allTiers(), tracker);

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
