package com.devmanchego.jtestforge.mutation;

import com.devmanchego.jtestforge.model.MutationReport;
import com.devmanchego.jtestforge.util.ExecutableResolver;
import com.devmanchego.jtestforge.util.ProcessResult;
import com.devmanchego.jtestforge.util.ProcessRunner;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Runs PIT via an external, self-contained wrapper fat jar —
 * jtestforge-specification.md §13.2, {@code harden.engine: standaloneWrapper}.
 *
 * <p>Exists for client environments that block {@code org.pitest} artifacts in their
 * internal Maven repository outright, which makes {@link PitestMavenPluginRunner}
 * unusable: the wrapper carries PITest inside itself as a fat jar and needs no pom change
 * or repository access in the target project.
 *
 * <p>The wrapper's own CLI is a client-supplied artifact, not something JTestForge
 * controls, so its argument contract has to be fixed <em>somewhere</em>. This class
 * assumes the flag set below - the same information {@code pitest-maven} itself takes as
 * system properties (§13.2), translated to CLI flags plus an explicit report path and
 * classpath so the wrapper needs no build-tool integration of its own. A deployment whose
 * wrapper uses a different contract needs a thin adapter in front of it, not a change
 * here.
 */
public final class StandaloneWrapperRunner implements MutationRunner {

    private static final Path REPORT_RELATIVE_PATH = Path.of("target", "pit-wrapper-reports", "mutations.xml");

    private final ProcessRunner processRunner;
    private final Path wrapperJar;
    private final MutationReportParser reportParser;
    private final String javaExecutable;

    public StandaloneWrapperRunner(ProcessRunner processRunner, Path wrapperJar) {
        this(processRunner, wrapperJar, "java", new MutationReportParser());
    }

    public StandaloneWrapperRunner(ProcessRunner processRunner, Path wrapperJar,
                                   String javaExecutable, MutationReportParser reportParser) {
        this.processRunner = Objects.requireNonNull(processRunner, "processRunner");
        this.wrapperJar = Objects.requireNonNull(wrapperJar, "wrapperJar");
        this.javaExecutable = Objects.requireNonNull(javaExecutable, "javaExecutable");
        this.reportParser = Objects.requireNonNull(reportParser, "reportParser");
    }

    @Override
    public MutationReport run(MutationRequest request) throws MutationException {
        Path reportFile = request.modulePath().resolve(REPORT_RELATIVE_PATH);
        deleteIfExists(reportFile);
        List<String> command = commandFor(request, reportFile);

        ProcessResult result;
        try {
            result = processRunner.run(command, request.modulePath(), java.util.Map.of(), null, request.timeout());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MutationException("Interrupted while running the PIT wrapper for " + request.modulePath(), e);
        }

        if (!Files.isRegularFile(reportFile)) {
            throw new MutationException("The PIT wrapper produced no report at " + reportFile
                    + " (exit code " + result.exitCode() + (result.timedOut() ? ", timed out" : "") + "): "
                    + result.stderr());
        }
        try {
            return reportParser.parse(reportFile);
        } catch (RuntimeException e) {
            throw new MutationException("Failed to parse the PIT wrapper's report at " + reportFile, e);
        }
    }

    /** Package-visible so the exact CLI translation can be asserted directly. */
    List<String> commandFor(MutationRequest request, Path reportFile) {
        List<String> command = new ArrayList<>();
        command.add(resolveJava());
        command.add("-jar");
        command.add(wrapperJar.toString());
        command.add("--module-path=" + request.modulePath());
        command.add("--target-classes=" + String.join(",", request.targetClasses()));
        command.add("--target-tests=" + String.join(",", request.targetTests()));
        command.add("--mutators=" + request.mutators());
        command.add("--output-formats=XML");
        command.add("--report-file=" + reportFile);
        request.historyFileIfPresent().ifPresent(historyFile -> {
            command.add("--history-input-file=" + historyFile);
            command.add("--history-output-file=" + historyFile);
        });
        return command;
    }

    /** Mirrors {@code PitestMavenPluginRunner}'s same fix - see its Javadoc for why. */
    private void deleteIfExists(Path reportFile) {
        try {
            Files.deleteIfExists(reportFile);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException("Failed to clear stale report at " + reportFile, e);
        }
    }

    private String resolveJava() {
        return ExecutableResolver.resolve(javaExecutable, ExecutableResolver.systemPathDirectories())
                .map(Path::toString)
                .orElse(javaExecutable);
    }
}
