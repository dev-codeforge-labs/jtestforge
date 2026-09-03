package com.devmanchego.jtestforge.build;

import com.devmanchego.jtestforge.model.SurefireTestResult;
import com.devmanchego.jtestforge.model.TestOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SurefireReportParserTest {

    private final SurefireReportParser parser = new SurefireReportParser();

    @Test
    void aPassingTestHasNoFailureDetail() throws Exception {
        SurefireTestResult result = resultNamed("appliesTheFlatFeeBelowTheThreshold");

        assertThat(result.outcome()).isEqualTo(TestOutcome.PASSED);
        assertThat(result.failureType()).isNull();
        assertThat(result.failureMessage()).isNull();
    }

    @Test
    void anAssertionFailureIsReportedAsSuchWithTypeAndMessage() throws Exception {
        SurefireTestResult result = resultNamed("rejectsNegativeAmounts");

        assertThat(result.outcome()).isEqualTo(TestOutcome.ASSERTION_FAILURE);
        assertThat(result.failureType()).isEqualTo("org.opentest4j.AssertionFailedError");
        assertThat(result.failureMessage()).contains("expected: <1> but was: <2>");
        assertThat(result.stackTraceHead()).contains("rejectsNegativeAmounts(PaymentServiceTest.java:42)");
    }

    @Test
    void aSkippedTestIsReportedAsSkipped() throws Exception {
        SurefireTestResult result = resultNamed("notYetImplemented");

        assertThat(result.outcome()).isEqualTo(TestOutcome.SKIPPED);
        assertThat(result.isFailure()).isFalse();
    }

    @Test
    void aSpringContextLoadFailureIsDistinguishedFromAPlainError() throws Exception {
        // §13.1 / phase 7: a BeanCreationException must not be reported the same way as
        // an assertion failure - it means the context never came up, not that the test's
        // own assertions were wrong.
        SurefireTestResult result = resultInFileNamed(
                "TEST-com.acme.web.OrderControllerWebTest.xml", "returnsTheOrderForAKnownId");

        assertThat(result.outcome()).isEqualTo(TestOutcome.CONTEXT_LOAD_FAILURE);
        assertThat(result.failureType()).isEqualTo("org.springframework.beans.factory.BeanCreationException");
        assertThat(result.isFailure()).isTrue();
    }

    @Test
    void anOrdinaryRuntimeExceptionIsAPlainErrorNotAContextLoadFailure() throws Exception {
        SurefireTestResult result = resultInFileNamed(
                "TEST-com.acme.web.OrderControllerWebTest.xml", "rejectsAnInvalidPayload");

        assertThat(result.outcome()).isEqualTo(TestOutcome.ERROR);
        assertThat(result.failureType()).isEqualTo("java.lang.NullPointerException");
    }

    @Test
    void theStackTraceHeadIsCappedRatherThanIncludingTheWholeTrace() throws Exception {
        SurefireTestResult result = resultInFileNamed(
                "TEST-com.acme.LongStackTraceTest.xml", "failsWithALongTrace");

        long lineCount = result.stackTraceHead().lines().count();
        assertThat(lineCount).isLessThanOrEqualTo(10);
        assertThat(result.stackTraceHead()).doesNotContain("line13");
    }

    @Test
    void parseDirectoryReadsEveryReportFileInAStableSortedOrder() throws URISyntaxException {
        Path dir = fixtureDir();

        List<SurefireTestResult> all = parser.parseDirectory(dir);

        assertThat(all).extracting(SurefireTestResult::className).contains(
                "com.acme.PaymentServiceTest", "com.acme.web.OrderControllerWebTest",
                "com.acme.LongStackTraceTest");
        assertThat(all.size()).isGreaterThanOrEqualTo(7);
    }

    @Test
    void aMissingDirectoryYieldsNoResultsRatherThanFailing(@TempDir Path dir) {
        assertThat(parser.parseDirectory(dir.resolve("does-not-exist"))).isEmpty();
    }

    @Test
    void classnameFallsBackToTheTestsuiteNameWhenATestcaseOmitsIt(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("TEST-com.acme.Foo.xml");
        Files.writeString(file, """
                <?xml version="1.0" encoding="UTF-8"?>
                <testsuite name="com.acme.Foo" tests="1">
                  <testcase name="aTest" time="0.01"/>
                </testsuite>
                """);

        SurefireTestResult result = parser.parseFile(file).get(0);

        assertThat(result.className()).isEqualTo("com.acme.Foo");
    }

    private SurefireTestResult resultNamed(String testName) throws URISyntaxException {
        return resultInFileNamed("TEST-com.acme.PaymentServiceTest.xml", testName);
    }

    private SurefireTestResult resultInFileNamed(String fileName, String testName) throws URISyntaxException {
        Path file = fixtureDir().resolve(fileName);
        return parser.parseFile(file).stream()
                .filter(r -> r.testName().equals(testName))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No such test in fixture: " + testName));
    }

    private Path fixtureDir() throws URISyntaxException {
        return Path.of(getClass().getClassLoader().getResource("surefire-reports").toURI());
    }
}
