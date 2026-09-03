package com.devmanchego.jtestforge.mutation;

import com.devmanchego.jtestforge.build.MavenRunner;
import com.devmanchego.jtestforge.model.MutationReport;
import com.devmanchego.jtestforge.util.ProcessRunner;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code -D} translation, verified directly, and a real process invocation for the
 * "runs Maven, then reads and parses whatever it produced" glue — jtestforge-specification.md
 * §13.1, §13.2.
 *
 * <p>The process invoked is a real {@code powershell -File} script, not a mock of
 * {@code MavenRunner} or {@code ProcessRunner}: this project's convention (§9, phase 9's
 * delivery-mode tests) is a real script file standing in for the external tool, never a
 * mocked process layer. {@code org.pitest:pitest-maven} itself is not resolvable offline
 * in this environment, so the script plays the plugin's part - it writes a known,
 * previously-parsed fixture report to the exact path the runner will look for it at, then
 * exits. What is genuinely exercised for real: process launch, working directory, output
 * capture, and - the actual production logic under test - locating and parsing whatever
 * ended up on disk afterward.
 */
class PitestMavenPluginRunnerTest {

    private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase().contains("win");
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final Path FIXTURE = Path.of("src/test/resources/pit-fixture/mutations.xml").toAbsolutePath();

    @Test
    void theGoalsCarryEveryRequestedProperty() {
        PitestMavenPluginRunner runner = new PitestMavenPluginRunner(
                new MavenRunner(new ProcessRunner(), "mvn", List.of()));
        MutationRequest request = new MutationRequest(Path.of("."),
                List.of("com.acme.Calculator"), List.of("com.acme.CalculatorTest"),
                Path.of(".jtestforge/pit-history.bin"), "DEFAULTS", TIMEOUT);

        List<String> goals = runner.goalsFor(request);

        assertThat(goals).containsExactly(
                "org.pitest:pitest-maven:mutationCoverage",
                "-DtargetClasses=com.acme.Calculator",
                "-DtargetTests=com.acme.CalculatorTest",
                "-DoutputFormats=XML",
                "-DtimestampedReports=false",
                "-Dmutators=DEFAULTS",
                "-DhistoryInputFile=" + Path.of(".jtestforge/pit-history.bin"),
                "-DhistoryOutputFile=" + Path.of(".jtestforge/pit-history.bin"));
    }

    @Test
    void multipleTargetClassesAndTestsAreCommaJoined() {
        PitestMavenPluginRunner runner = new PitestMavenPluginRunner(
                new MavenRunner(new ProcessRunner(), "mvn", List.of()));
        MutationRequest request = new MutationRequest(Path.of("."),
                List.of("com.acme.Calculator", "com.acme.Ledger"),
                List.of("com.acme.CalculatorTest", "com.acme.LedgerTest"),
                null, "DEFAULTS", TIMEOUT);

        List<String> goals = runner.goalsFor(request);

        assertThat(goals).contains("-DtargetClasses=com.acme.Calculator,com.acme.Ledger",
                "-DtargetTests=com.acme.CalculatorTest,com.acme.LedgerTest");
    }

    @Test
    void withNoHistoryFileNeitherHistoryPropertyIsPassed() {
        PitestMavenPluginRunner runner = new PitestMavenPluginRunner(
                new MavenRunner(new ProcessRunner(), "mvn", List.of()));
        MutationRequest request = new MutationRequest(Path.of("."),
                List.of("com.acme.Calculator"), List.of("com.acme.CalculatorTest"),
                null, "DEFAULTS", TIMEOUT);

        assertThat(runner.goalsFor(request)).noneMatch(goal -> goal.startsWith("-DhistoryInputFile")
                || goal.startsWith("-DhistoryOutputFile"));
    }

    @Test
    void aRealInvocationLocatesAndParsesWhateverLandedAtTheReportPath(@TempDir Path moduleDir) throws IOException {
        Assumptions.assumeTrue(WINDOWS, "the fake-mvn fixture in this test is a PowerShell script");

        Path script = writeFakeMvnScript(moduleDir);
        MavenRunner mavenRunner = new MavenRunner(new ProcessRunner(), "powershell", List.of(
                "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File", script.toString()));
        PitestMavenPluginRunner runner = new PitestMavenPluginRunner(mavenRunner);
        MutationRequest request = new MutationRequest(moduleDir,
                List.of("com.acme.Calculator"), List.of("com.acme.CalculatorTest"),
                null, "DEFAULTS", TIMEOUT);

        MutationReport report;
        try {
            report = runner.run(request);
        } catch (MutationException e) {
            throw new AssertionError(e);
        }

        assertThat(report).isEqualTo(new MutationReportParser().parse(FIXTURE));
    }

    @Test
    void aStaleReportFromAnEarlierInvocationIsNeverReadAsThisRunsResult(@TempDir Path moduleDir) throws IOException {
        // §12's Surefire lesson, applied here: a run that fails before PIT ever writes a
        // report must not be read as a previous run's results.
        Assumptions.assumeTrue(WINDOWS, "the fake-mvn fixture in this test is a PowerShell script");

        Path reportDir = moduleDir.resolve("target").resolve("pit-reports");
        Files.createDirectories(reportDir);
        Files.copy(FIXTURE, reportDir.resolve("mutations.xml"));

        Path script = writeFailingScript(moduleDir);
        MavenRunner mavenRunner = new MavenRunner(new ProcessRunner(), "powershell", List.of(
                "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File", script.toString()));
        PitestMavenPluginRunner runner = new PitestMavenPluginRunner(mavenRunner);
        MutationRequest request = new MutationRequest(moduleDir,
                List.of("com.acme.Calculator"), List.of("com.acme.CalculatorTest"),
                null, "DEFAULTS", TIMEOUT);

        org.junit.jupiter.api.Assertions.assertThrows(MutationException.class, () -> runner.run(request));
    }

    private Path writeFakeMvnScript(Path moduleDir) throws IOException {
        Path script = moduleDir.resolve("fake-mvn.ps1");
        String fixturePath = FIXTURE.toString().replace("\\", "/");
        Files.writeString(script, """
                New-Item -ItemType Directory -Force -Path "target/pit-reports" | Out-Null
                Copy-Item -Path "%s" -Destination "target/pit-reports/mutations.xml"
                exit 0
                """.formatted(fixturePath));
        return script;
    }

    private Path writeFailingScript(Path moduleDir) throws IOException {
        Path script = moduleDir.resolve("fake-mvn-fails.ps1");
        Files.writeString(script, "exit 1\n");
        return script;
    }
}
