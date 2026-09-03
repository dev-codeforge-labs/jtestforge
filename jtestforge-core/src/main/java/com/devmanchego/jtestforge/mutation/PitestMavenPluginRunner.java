package com.devmanchego.jtestforge.mutation;

import com.devmanchego.jtestforge.build.MavenRunResult;
import com.devmanchego.jtestforge.build.MavenRunner;
import com.devmanchego.jtestforge.model.MutationReport;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Runs PIT via the {@code pitest-maven} plugin — jtestforge-specification.md §13.2,
 * {@code harden.engine: pitestMavenPlugin} (the default).
 *
 * <p>Requires the plugin (and {@code pitest-junit5-plugin} for a JUnit 5 module) to be
 * resolvable from the target module's own Maven repositories - this is exactly the
 * requirement {@code StandaloneWrapperRunner} exists to route around when a client's
 * internal repository blocks {@code org.pitest} artifacts entirely.
 *
 * <p>{@code -DtimestampedReports=false} is passed even though the specification's own
 * property list (§13.2) does not mention it: without it, PIT writes each run's XML under
 * a fresh {@code target/pit-reports/<timestamp>/} directory, and there would be no fixed
 * path this class could read the report back from. Forcing a flat, predictable
 * {@code target/pit-reports/mutations.xml} is the same "read from a stable machine
 * location, not console output or a moving path" discipline {@code MavenModuleBuild}
 * already applies to Surefire and JaCoCo (§13.1).
 */
public final class PitestMavenPluginRunner implements MutationRunner {

    private static final Path REPORT_RELATIVE_PATH = Path.of("target", "pit-reports", "mutations.xml");

    private final MavenRunner mavenRunner;
    private final MutationReportParser reportParser;

    public PitestMavenPluginRunner(MavenRunner mavenRunner) {
        this(mavenRunner, new MutationReportParser());
    }

    public PitestMavenPluginRunner(MavenRunner mavenRunner, MutationReportParser reportParser) {
        this.mavenRunner = Objects.requireNonNull(mavenRunner, "mavenRunner");
        this.reportParser = Objects.requireNonNull(reportParser, "reportParser");
    }

    @Override
    public MutationReport run(MutationRequest request) throws MutationException {
        Path reportFile = request.modulePath().resolve(REPORT_RELATIVE_PATH);
        deleteIfExists(reportFile);

        MavenRunResult result = mavenRunner.run(request.modulePath(), goalsFor(request), request.timeout());

        if (!Files.isRegularFile(reportFile)) {
            throw new MutationException("PIT produced no report at " + reportFile + " (mvn exit code "
                    + result.exitCode() + (result.timedOut() ? ", timed out" : "") + ")");
        }
        try {
            return reportParser.parse(reportFile);
        } catch (RuntimeException e) {
            throw new MutationException("Failed to parse PIT report at " + reportFile, e);
        }
    }

    /**
     * Deletes any report left by a previous invocation before this one starts. Mirrors
     * {@code MavenModuleBuild}'s Surefire handling: without this, a run that fails before
     * PIT ever writes a report (the plugin fails to resolve, a compile error) would be
     * read as the previous run's results - reporting mutants as killed or survived on
     * evidence from a build that predates the current source entirely.
     */
    private void deleteIfExists(Path reportFile) {
        try {
            Files.deleteIfExists(reportFile);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException("Failed to clear stale report at " + reportFile, e);
        }
    }

    /** Package-visible so the exact {@code -D} translation can be asserted directly. */
    List<String> goalsFor(MutationRequest request) {
        List<String> goals = new ArrayList<>();
        goals.add("org.pitest:pitest-maven:mutationCoverage");
        goals.add("-DtargetClasses=" + String.join(",", request.targetClasses()));
        goals.add("-DtargetTests=" + String.join(",", request.targetTests()));
        goals.add("-DoutputFormats=XML");
        goals.add("-DtimestampedReports=false");
        goals.add("-Dmutators=" + request.mutators());
        request.historyFileIfPresent().ifPresent(historyFile -> {
            // Same path for both: each run reads what the previous one wrote, then
            // rewrites it, which is what makes incremental analysis incremental (§13.3).
            goals.add("-DhistoryInputFile=" + historyFile);
            goals.add("-DhistoryOutputFile=" + historyFile);
        });
        return goals;
    }
}
