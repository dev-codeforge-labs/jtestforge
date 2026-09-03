package com.devmanchego.jtestforge.mutation;

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
 * The CLI translation, verified directly, and a real process invocation for the
 * "runs the wrapper, then reads and parses whatever it produced" glue —
 * jtestforge-specification.md §13.2.
 *
 * <p>Same real-script convention as {@code PitestMavenPluginRunnerTest}: the wrapper's own
 * CLI is a client-supplied contract this class assumes (see its own Javadoc), so the test
 * script plays the wrapper's part by reading the {@code --report-file} argument this class
 * actually passed it and writing the known fixture there - which exercises the real
 * translation end to end, not just half of it.
 */
class StandaloneWrapperRunnerTest {

    private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase().contains("win");
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final Path FIXTURE = Path.of("src/test/resources/pit-fixture/mutations.xml").toAbsolutePath();
    private static final Path WRAPPER_JAR = Path.of("pit-wrapper.jar");

    @Test
    void theCommandCarriesJavaJarAndEveryRequestedFlag() {
        StandaloneWrapperRunner runner = new StandaloneWrapperRunner(new ProcessRunner(), WRAPPER_JAR);
        MutationRequest request = new MutationRequest(Path.of("."),
                List.of("com.acme.Calculator"), List.of("com.acme.CalculatorTest"),
                Path.of(".jtestforge/pit-history.bin"), "DEFAULTS", TIMEOUT);
        Path reportFile = Path.of("target/pit-wrapper-reports/mutations.xml");

        List<String> command = runner.commandFor(request, reportFile);

        // element 0 is "java" resolved to a real launchable path (§13.1's own reasoning:
        // ProcessBuilder does not do PATHEXT search for a bare name on Windows) - its
        // exact value is machine-dependent, so only that it names a java executable is
        // asserted here.
        assertThat(command.get(0)).matches(".*[/\\\\]?java(\\.exe)?$");
        assertThat(command.subList(1, command.size())).containsExactly(
                "-jar", WRAPPER_JAR.toString(),
                "--module-path=" + Path.of("."),
                "--target-classes=com.acme.Calculator",
                "--target-tests=com.acme.CalculatorTest",
                "--mutators=DEFAULTS",
                "--output-formats=XML",
                "--report-file=" + reportFile,
                "--history-input-file=" + Path.of(".jtestforge/pit-history.bin"),
                "--history-output-file=" + Path.of(".jtestforge/pit-history.bin"));
    }

    @Test
    void withNoHistoryFileNeitherHistoryFlagIsPassed() {
        StandaloneWrapperRunner runner = new StandaloneWrapperRunner(new ProcessRunner(), WRAPPER_JAR);
        MutationRequest request = new MutationRequest(Path.of("."),
                List.of("com.acme.Calculator"), List.of("com.acme.CalculatorTest"),
                null, "DEFAULTS", TIMEOUT);

        List<String> command = runner.commandFor(request, Path.of("target/report/mutations.xml"));

        assertThat(command).noneMatch(arg -> arg.startsWith("--history-input-file")
                || arg.startsWith("--history-output-file"));
    }

    @Test
    void aRealInvocationLocatesAndParsesWhateverLandedAtTheReportedPath(@TempDir Path moduleDir) throws IOException {
        Assumptions.assumeTrue(WINDOWS, "the fake-wrapper fixture in this test is a PowerShell script");

        Path javaShim = writeFakeJavaShim(moduleDir, writeFakeWrapperScript(moduleDir));
        StandaloneWrapperRunner runner = new StandaloneWrapperRunner(new ProcessRunner(), WRAPPER_JAR,
                javaShim.toString(), new MutationReportParser());
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
        Assumptions.assumeTrue(WINDOWS, "the fake-wrapper fixture in this test is a PowerShell script");

        Path reportDir = moduleDir.resolve("target").resolve("pit-wrapper-reports");
        Files.createDirectories(reportDir);
        Files.copy(FIXTURE, reportDir.resolve("mutations.xml"));

        Path javaShim = writeFakeJavaShim(moduleDir, writeFailingScript(moduleDir));
        StandaloneWrapperRunner runner = new StandaloneWrapperRunner(new ProcessRunner(), WRAPPER_JAR,
                javaShim.toString(), new MutationReportParser());
        MutationRequest request = new MutationRequest(moduleDir,
                List.of("com.acme.Calculator"), List.of("com.acme.CalculatorTest"),
                null, "DEFAULTS", TIMEOUT);

        org.junit.jupiter.api.Assertions.assertThrows(MutationException.class, () -> runner.run(request));
    }

    /**
     * A thin {@code .cmd} shim standing in for the {@code java} executable this class
     * would otherwise launch, forwarding every argument verbatim to a real PowerShell
     * script via {@code %*}. {@link ProcessBuilder} on Windows launches a {@code .cmd}
     * file directly given its literal path (confirmed empirically against this JDK), but
     * batch's own argument parsing is too fragile for the logic itself - PowerShell's
     * {@code -File} positional binding is the same reliable mechanism phase 9's delivery-
     * mode tests already depend on.
     */
    private Path writeFakeJavaShim(Path moduleDir, Path logicScript) throws IOException {
        Path shim = moduleDir.resolve("fake-java.cmd");
        Files.writeString(shim, "@echo off\r\n"
                + "powershell -NoProfile -NonInteractive -ExecutionPolicy Bypass -File \""
                + logicScript + "\" %*\r\n");
        return shim;
    }

    private Path writeFakeWrapperScript(Path moduleDir) throws IOException {
        Path script = moduleDir.resolve("fake-wrapper.ps1");
        String fixturePath = FIXTURE.toString().replace("\\", "/");
        // The real launch is `java -jar <wrapperJar> --report-file=<path> ...`; through
        // the shim this receives the SAME arguments (minus "java" itself) as its own
        // $args, so --report-file is found exactly where the production code put it.
        Files.writeString(script, """
                param([Parameter(ValueFromRemainingArguments=$true)]$rest)
                $reportArg = $rest | Where-Object { $_ -like '--report-file=*' }
                $reportFile = $reportArg.Substring('--report-file='.Length)
                New-Item -ItemType Directory -Force -Path (Split-Path $reportFile) | Out-Null
                Copy-Item -Path "%s" -Destination $reportFile
                exit 0
                """.formatted(fixturePath));
        return script;
    }

    private Path writeFailingScript(Path moduleDir) throws IOException {
        Path script = moduleDir.resolve("fake-wrapper-fails.ps1");
        Files.writeString(script, "exit 1\n");
        return script;
    }
}
