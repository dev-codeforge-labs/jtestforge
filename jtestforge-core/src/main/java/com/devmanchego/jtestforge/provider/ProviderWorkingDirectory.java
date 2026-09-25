package com.devmanchego.jtestforge.provider;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Decides which directory an AI CLI is started in.
 *
 * <p>Modern AI CLIs are agentic harnesses, not completion endpoints: they treat their
 * working directory as a workspace and will grep and read files in it before answering.
 * Pointed at the target module, a prompt like "write tests for this class" makes the CLI
 * explore a large legacy source tree - including a {@code target/} directory JTestForge
 * itself keeps repopulating with every {@code test-compile} - through however many tool
 * round-trips it decides it needs. Measured in practice against a real module: minutes per
 * call, growing call over call, for prompts the same CLI answers in seconds when invoked
 * by hand.
 *
 * <p>That exploration buys nothing here. JTestForge already assembles every piece of
 * context the model needs INTO the prompt (§6: the full production class, collaborator
 * signatures, the existing test class, detected framework versions). An empty directory
 * gives the CLI nothing to explore and nothing to be slow about, and it is the default for
 * exactly that reason.
 *
 * <p>Isolation alone does not disable an agentic CLI's tools - it only removes anything
 * worth pointing them at. Turning the tools off is the CLI's own configuration to make;
 * see the {@code aiProvider} block of the generated {@code jtestforge.yaml} for the
 * Gemini CLI's policy-file syntax.
 */
public final class ProviderWorkingDirectory {

    /** Directory name created under the state dir. Visible on purpose: a CLI that writes logs or session files there is worth being able to find. */
    static final String ISOLATED_DIR_NAME = "ai-cwd";

    private ProviderWorkingDirectory() {
    }

    /**
     * @param isolate    {@code aiProvider.isolateWorkingDirectory}
     * @param modulePath used as-is when isolation is off, preserving the pre-isolation
     *                   behaviour for a provider that genuinely wants the module in view
     * @param stateDir   parent of the empty directory created when isolation is on
     * @throws IOException if the isolated directory cannot be created; the caller decides
     *                     whether that is worth failing the run over, since falling back
     *                     to the module path still produces correct tests, only slower
     */
    public static Path resolve(boolean isolate, Path modulePath, Path stateDir) throws IOException {
        if (!isolate) {
            return modulePath;
        }
        Path isolated = stateDir.resolve(ISOLATED_DIR_NAME);
        Files.createDirectories(isolated);
        return isolated;
    }
}
