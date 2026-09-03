package com.devmanchego.jtestforge.report;

import com.devmanchego.jtestforge.model.Baseline;
import com.devmanchego.jtestforge.model.RunState;
import com.devmanchego.jtestforge.model.UnitStatus;

import java.util.List;
import java.util.Locale;

/**
 * Renders {@link ReportData} to a single, self-contained HTML page — jtestforge-
 * specification.md §15. Inline {@code <style>}, no external stylesheet, script, font or
 * image reference: the report has to open correctly from a plain {@code file://} URL on a
 * machine with no network access, which is the common case for a CI artifact.
 */
public final class HtmlReportRenderer {

    private static final String STYLE = """
            body { font-family: -apple-system, Segoe UI, Helvetica, Arial, sans-serif; margin: 2rem; color: #1a1a1a; }
            h1, h2, h3 { color: #1a1a1a; }
            table { border-collapse: collapse; margin: 0.5rem 0 1.5rem; }
            th, td { border: 1px solid #ccc; padding: 0.35rem 0.75rem; text-align: left; }
            th { background: #f0f0f0; }
            code { background: #f5f5f5; padding: 0.1rem 0.3rem; border-radius: 3px; }
            .empty { color: #666; font-style: italic; }
            section { margin-bottom: 2rem; }
            """;

    private final ReportDataBuilder dataBuilder = new ReportDataBuilder();

    public String render(RunState state) {
        return render(dataBuilder.build(state));
    }

    public String render(ReportData data) {
        StringBuilder html = new StringBuilder();
        html.append("<!doctype html>\n<html lang=\"en\">\n<head>\n<meta charset=\"utf-8\">\n");
        html.append("<title>JTestForge report - ").append(escape(data.runId())).append("</title>\n");
        html.append("<style>\n").append(STYLE).append("</style>\n</head>\n<body>\n");

        renderHeadline(html, data);
        renderStatusCounts(html, data);
        renderTierTable(html, data);
        renderDiscardReasons(html, data);
        renderClassSections(html, data);
        renderSpringSection(html, data);

        html.append("</body>\n</html>\n");
        return html.toString();
    }

    private void renderHeadline(StringBuilder html, ReportData data) {
        html.append("<h1>JTestForge report - ").append(escape(String.valueOf(data.phase())))
                .append(" run ").append(escape(data.runId())).append("</h1>\n");
        html.append("<p>Module: <code>").append(escape(data.modulePath())).append("</code><br>\n");
        html.append("Provider: <code>").append(escape(data.providerId())).append("</code><br>\n");
        html.append("Started: ").append(data.startedAt()).append("<br>\n");
        html.append("Updated: ").append(data.updatedAt()).append("</p>\n");

        html.append("<section><h2>Headline</h2>\n<ul>\n");
        Baseline baseline = data.baseline();
        html.append("<li>Baseline line coverage: ").append(percent(baseline.lineCoverage())).append("</li>\n");
        html.append("<li>Baseline branch coverage: ").append(percent(baseline.branchCoverage())).append("</li>\n");
        html.append("<li>Newly covered this run: ").append(data.linesCoveredThisRun()).append(" line(s), ")
                .append(data.branchesCoveredThisRun()).append(" branch(es)</li>\n");
        html.append("<li>Framework-semantic gaps: ").append(baseline.openFrameworkSemanticGaps())
                .append(" open at baseline, ").append(data.gapsClosedThisRun()).append(" closed this run, ")
                .append(data.gapsStillOpen()).append(" still open</li>\n");
        if (baseline.mutationScore() != null) {
            html.append("<li>Baseline mutation score: ").append(percent(baseline.mutationScore())).append("</li>\n");
        }
        html.append("<li>Mutants killed this run: ").append(data.mutantsKilledThisRun()).append("</li>\n");
        html.append("</ul></section>\n");
    }

    private void renderStatusCounts(StringBuilder html, ReportData data) {
        html.append("<section><h2>Units</h2>\n");
        html.append("<p>Total: ").append(data.totalUnits()).append(", kept: ").append(data.unitsKept())
                .append("</p>\n");
        if (data.statusCounts().isEmpty()) {
            html.append("<p class=\"empty\">No units were discovered for this run.</p>\n");
        } else {
            html.append("<table><tr><th>Status</th><th>Count</th></tr>\n");
            for (UnitStatus status : UnitStatus.values()) {
                Integer count = data.statusCounts().get(status);
                if (count != null && count > 0) {
                    html.append("<tr><td>").append(status).append("</td><td>").append(count).append("</td></tr>\n");
                }
            }
            html.append("</table>\n");
        }
        html.append("</section>\n");
    }

    private void renderTierTable(StringBuilder html, ReportData data) {
        html.append("<section><h2>Per-tier cost</h2>\n");
        if (data.tierSummaries().isEmpty()) {
            html.append("<p class=\"empty\">No tier attempted any unit.</p>\n");
        } else {
            html.append("<table><tr><th>Tier</th><th>Attempted</th><th>Kept</th><th>Discarded</th>"
                    + "<th>Wall-clock</th></tr>\n");
            for (ReportData.TierSummary tier : data.tierSummaries()) {
                html.append("<tr><td>").append(tier.tier()).append("</td><td>").append(tier.attempted())
                        .append("</td><td>").append(tier.kept()).append("</td><td>").append(tier.discarded())
                        .append("</td><td>").append(duration(tier.wallClockMillis())).append("</td></tr>\n");
            }
            html.append("</table>\n");
        }
        html.append("</section>\n");
    }

    private void renderDiscardReasons(StringBuilder html, ReportData data) {
        html.append("<section><h2>Discard-reason breakdown</h2>\n");
        if (data.discardReasons().isEmpty()) {
            html.append("<p class=\"empty\">Nothing was discarded this run.</p>\n");
        } else {
            html.append("<table><tr><th>Reason</th><th>Count</th></tr>\n");
            for (ReportData.DiscardReasonCount reason : data.discardReasons()) {
                html.append("<tr><td>").append(escape(reason.category())).append("</td><td>")
                        .append(reason.count()).append("</td></tr>\n");
            }
            html.append("</table>\n");
        }
        html.append("</section>\n");
    }

    private void renderClassSections(StringBuilder html, ReportData data) {
        html.append("<section><h2>Per class</h2>\n");
        if (data.classSummaries().isEmpty()) {
            html.append("<p class=\"empty\">No class was touched this run.</p>\n");
        }
        for (ReportData.ClassSummary summary : data.classSummaries()) {
            html.append("<h3><code>").append(escape(summary.className())).append("</code></h3>\n<ul>\n");
            renderListItem(html, "Tests added", summary.testsAdded());
            renderListItem(html, "Discarded", summary.discarded());
            renderListItem(html, "Mutants killed", summary.mutantsKilled());
            html.append("</ul>\n");
        }
        html.append("</section>\n");
    }

    private void renderListItem(StringBuilder html, String label, List<String> items) {
        if (items.isEmpty()) {
            html.append("<li>").append(label).append(": <span class=\"empty\">none</span></li>\n");
            return;
        }
        html.append("<li>").append(label).append(":<ul>\n");
        for (String item : items) {
            html.append("<li>").append(escape(item)).append("</li>\n");
        }
        html.append("</ul></li>\n");
    }

    private void renderSpringSection(StringBuilder html, ReportData data) {
        if (data.springTiers() == null) {
            return;
        }
        html.append("<section><h2>Spring context-load accounting</h2>\n<ul>\n");
        html.append("<li>Context loads: ").append(data.springTiers().contextLoads())
                .append(" / ").append(data.springTiers().contextLoadBudget()).append("</li>\n");
        if (!data.springTiers().unavailable().isEmpty()) {
            html.append("<li>Unavailable tiers:<ul>\n");
            for (var entry : data.springTiers().unavailable().entrySet()) {
                html.append("<li>").append(entry.getKey()).append(": ").append(escape(entry.getValue()))
                        .append("</li>\n");
            }
            html.append("</ul></li>\n");
        }
        html.append("</ul></section>\n");
    }

    private String percent(double fraction) {
        return String.format(Locale.ROOT, "%.1f%%", fraction * 100.0);
    }

    private String duration(long millis) {
        if (millis < 1000) {
            return millis + " ms";
        }
        return String.format(Locale.ROOT, "%.1f s", millis / 1000.0);
    }

    /** Escapes the five HTML-significant characters; every value rendered here can contain arbitrary text. */
    private String escape(String text) {
        return text == null ? "" : text
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
