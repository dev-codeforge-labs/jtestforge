package com.devmanchego.jtestforge.report;

import com.devmanchego.jtestforge.model.Baseline;
import com.devmanchego.jtestforge.model.Phase;
import com.devmanchego.jtestforge.model.RunState;
import com.devmanchego.jtestforge.model.SpringTierState;
import com.devmanchego.jtestforge.model.Tier;
import com.devmanchego.jtestforge.model.UnitStatus;
import com.devmanchego.jtestforge.model.WorkUnit;
import com.devmanchego.jtestforge.model.WorkUnitId;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * jtestforge-implementation-plan.md phase 16's own test criteria, against both output
 * formats: a fixture renders deterministically; a run that kept nothing still renders a
 * meaningful report; the open-gaps figure survives a run where nothing was generated.
 */
class ReportRendererTest {

    private static final Instant NOW = Instant.parse("2026-08-31T10:00:00Z");

    private final MarkdownReportRenderer markdown = new MarkdownReportRenderer();
    private final HtmlReportRenderer html = new HtmlReportRenderer();

    @Test
    void aFixtureStateFileRendersDeterministicallyInMarkdown() {
        RunState state = fixtureState();

        String first = markdown.render(state);
        String second = markdown.render(state);

        assertThat(first).isEqualTo(second);
        assertThat(first).contains("com.acme.PaymentService").contains("classify_a")
                .contains("NO_ASSERTION").contains("PLAIN_UNIT").contains("WEB_SLICE");
    }

    @Test
    void aFixtureStateFileRendersDeterministicallyInHtml() {
        RunState state = fixtureState();

        String first = html.render(state);
        String second = html.render(state);

        assertThat(first).isEqualTo(second);
        assertThat(first).startsWith("<!doctype html>");
        assertThat(first).contains("<style>").doesNotContain("<script").doesNotContain("http://").doesNotContain("https://");
    }

    @Test
    void aRunThatKeptNothingStillRendersAMeaningfulMarkdownReport() {
        RunState state = allFailedState();

        String report = markdown.render(state);

        assertThat(report).contains("Total: 2, kept: 0");
        assertThat(report).contains("FAILED_COMPILE").contains("DISCARDED_NO_VALUE");
        assertThat(report).doesNotContain("Exception").doesNotContain("null\n");
    }

    @Test
    void aRunThatKeptNothingStillRendersAMeaningfulHtmlReport() {
        String report = html.render(allFailedState());

        assertThat(report).contains("Total: 2, kept: 0");
        assertThat(report).contains("FAILED_COMPILE");
    }

    @Test
    void theOpenGapsFigureSurvivesARunWhereNothingWasGeneratedAtAll() {
        RunState state = RunState.startNew("run-1", NOW, Phase.GENERATE, "C:/app", "sha256:cfg", "claude",
                SpringTierState.springDisabled(40), List.of())
                .withBaseline(new Baseline(0.3, 0.2, null, 9));

        String markdownReport = markdown.render(state);
        String htmlReport = html.render(state);

        assertThat(markdownReport).contains("9 open at baseline").contains("9 still open");
        assertThat(htmlReport).contains("9 open at baseline").contains("9 still open");
        assertThat(markdownReport).contains("No units were discovered");
    }

    @Test
    void anHtmlValueContainingMarkupCharactersIsEscaped() {
        RunState state = stateWith(Baseline.notMeasured(), List.of(
                unit("classify", UnitStatus.FAILED_COMPILE,
                        List.of("NO_ASSERTION rejected foo: uses <script>alert(1)</script>"))));

        String report = html.render(state);

        assertThat(report).doesNotContain("<script>alert");
        assertThat(report).contains("&lt;script&gt;");
    }

    @Test
    void aStatusThatNeverOccursIsOmittedFromTheStatusTableRatherThanShownAsZero() {
        RunState state = allFailedState();

        String report = markdown.render(state);

        assertThat(report).doesNotContain("| DONE | 0 |").doesNotContain("| PENDING | 0 |");
    }

    // --- fixtures -------------------------------------------------------------------

    private RunState fixtureState() {
        return stateWith(new Baseline(0.412, 0.301, null, 5), List.of(
                unitFull("classify", Tier.PLAIN_UNIT, UnitStatus.DONE,
                        List.of("classify_a"), List.of("NO_ASSERTION rejected foo: no assertion"),
                        List.of("gap-1"), List.of(), 7, 2, 500),
                unitFull("findById", Tier.WEB_SLICE, UnitStatus.DONE,
                        List.of(), List.of(), List.of("gap-2"), List.of(), 0, 0, 1500)));
    }

    private RunState allFailedState() {
        return stateWith(Baseline.notMeasured(), List.of(
                unit("classify", UnitStatus.FAILED_COMPILE, List.of("cannot find symbol")),
                unit("settle", UnitStatus.DISCARDED_NO_VALUE, List.of())));
    }

    private RunState stateWith(Baseline baseline, List<WorkUnit> units) {
        return RunState.startNew("run-1", NOW, Phase.GENERATE, "C:/app", "sha256:cfg", "claude",
                SpringTierState.springDisabled(40), units).withBaseline(baseline);
    }

    private WorkUnit unit(String method, UnitStatus status, List<String> discarded) {
        return unitFull(method, Tier.PLAIN_UNIT, status, List.of(), discarded, List.of(), List.of(), 0, 0, 100);
    }

    private WorkUnit unitFull(String method, Tier tier, UnitStatus status, List<String> addedTests,
                              List<String> discarded, List<String> gapsClosed, List<String> mutantsKilled,
                              int linesDelta, int branchesDelta, long durationMillis) {
        WorkUnitId id = WorkUnitId.of("com.acme.PaymentService", method + "()", tier);
        return new WorkUnit(id, "PaymentServiceTest.java", "PaymentService.java", "sha256:src", "sha256:test",
                status, 1, addedTests, List.of(), discarded, gapsClosed, mutantsKilled,
                linesDelta, branchesDelta, durationMillis, null, null);
    }
}
