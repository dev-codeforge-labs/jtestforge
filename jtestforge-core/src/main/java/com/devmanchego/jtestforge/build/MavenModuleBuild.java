package com.devmanchego.jtestforge.build;

import com.devmanchego.jtestforge.coverage.JacocoReportParser;
import com.devmanchego.jtestforge.model.ClassCoverage;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The real {@link ModuleBuild}: drives Maven in the target module and reads what it wrote
 * — jtestforge-specification.md §13.1.
 *
 * <p>Test outcomes always come from {@code target/surefire-reports/*.xml} and never from
 * Maven's exit code alone. A non-zero exit tells you the build failed, not <em>which</em>
 * test failed or why - and the repair prompt needs the latter.
 */
public final class MavenModuleBuild implements ModuleBuild {

    private static final Path SUREFIRE_REPORTS = Path.of("target", "surefire-reports");
    private static final Path JACOCO_REPORT = Path.of("target", "site", "jacoco", "jacoco.xml");

    private final MavenRunner mavenRunner;
    private final SurefireReportParser surefireReportParser;
    private final JacocoReportParser jacocoReportParser;
    private final Path modulePath;
    private final Duration buildTimeout;

    public MavenModuleBuild(MavenRunner mavenRunner, Path modulePath, Duration buildTimeout) {
        this(mavenRunner, new SurefireReportParser(), new JacocoReportParser(), modulePath, buildTimeout);
    }

    public MavenModuleBuild(MavenRunner mavenRunner, SurefireReportParser surefireReportParser,
                            JacocoReportParser jacocoReportParser, Path modulePath, Duration buildTimeout) {
        this.mavenRunner = Objects.requireNonNull(mavenRunner, "mavenRunner");
        this.surefireReportParser = Objects.requireNonNull(surefireReportParser, "surefireReportParser");
        this.jacocoReportParser = Objects.requireNonNull(jacocoReportParser, "jacocoReportParser");
        this.modulePath = Objects.requireNonNull(modulePath, "modulePath");
        this.buildTimeout = Objects.requireNonNull(buildTimeout, "buildTimeout");
    }

    @Override
    public CompileOutcome compileTests() {
        MavenRunResult result = mavenRunner.run(modulePath, List.of("test-compile"), buildTimeout);
        return result.succeeded()
                ? CompileOutcome.success()
                : CompileOutcome.failure(result.compilerErrors(), rawLog(result));
    }

    /**
     * The complete build output, kept alongside {@code compilerErrors} rather than instead
     * of them: {@link CompilerErrorParser} only recognises {@code javac}'s own diagnostic
     * shape, so a build that fails for any other reason (an annotation processor, a plugin
     * execution, a dependency it cannot resolve) would otherwise leave the fix-compilation
     * prompt - and any later diagnosis - with nothing to act on at all.
     */
    private String rawLog(MavenRunResult result) {
        StringBuilder log = new StringBuilder();
        if (result.timedOut()) {
            log.append("(build timed out)\n");
        } else {
            log.append("Exit code: ").append(result.exitCode()).append('\n');
        }
        log.append("\n--- stdout ---\n").append(result.stdout());
        log.append("\n--- stderr ---\n").append(result.stderr());
        return log.toString();
    }

    @Override
    public TestRunOutcome runScopedTests(String testClassSimpleName, List<String> methodNames) {
        clearPreviousReports();
        MavenRunResult result = mavenRunner.run(modulePath,
                List.of("test", ScopedTestSelector.buildArgument(testClassSimpleName, methodNames)),
                buildTimeout);
        return readSurefireReports(result);
    }

    @Override
    public TestRunOutcome runFullSuite() {
        clearPreviousReports();
        MavenRunResult result = mavenRunner.run(modulePath, List.of("test"), buildTimeout);
        return readSurefireReports(result);
    }

    @Override
    public Optional<ClassCoverage> measureCoverage(String classFqn) {
        mavenRunner.run(modulePath, List.of("jacoco:report"), buildTimeout);
        Path reportFile = modulePath.resolve(JACOCO_REPORT);
        if (!java.nio.file.Files.isRegularFile(reportFile)) {
            return Optional.empty();
        }
        return jacocoReportParser.parse(reportFile).stream()
                .filter(coverage -> coverage.fqn().equals(classFqn))
                .findFirst();
    }

    /**
     * Maven's own exit status travels with the Surefire results on purpose - see
     * {@link TestRunOutcome}: a build that never reached the test phase reports no
     * failures, which is indistinguishable from success if only the reports are read.
     */
    private TestRunOutcome readSurefireReports(MavenRunResult result) {
        return new TestRunOutcome(surefireReportParser.parseDirectory(modulePath.resolve(SUREFIRE_REPORTS)),
                result.succeeded(), rawLog(result));
    }

    /**
     * Surefire leaves the previous run's XML in place when the current run fails before
     * reaching a test. Without clearing, a scoped run that never executed would be read as
     * the previous run's results - reporting a unit as passing on evidence from a build
     * that predates it.
     */
    private void clearPreviousReports() {
        Path reportsDir = modulePath.resolve(SUREFIRE_REPORTS);
        if (!java.nio.file.Files.isDirectory(reportsDir)) {
            return;
        }
        try (var files = java.nio.file.Files.list(reportsDir)) {
            for (Path file : files.filter(java.nio.file.Files::isRegularFile).toList()) {
                if (file.getFileName().toString().endsWith(".xml")) {
                    java.nio.file.Files.deleteIfExists(file);
                }
            }
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException("Failed to clear " + reportsDir, e);
        }
    }
}
