package com.devmanchego.jtestforge.report;

import com.devmanchego.jtestforge.model.Baseline;
import com.devmanchego.jtestforge.model.RunState;
import com.devmanchego.jtestforge.model.UnitStatus;

import java.util.List;
import java.util.Locale;

/**
 * Renders {@link ReportData} to Markdown — jtestforge-specification.md §15.
 *
 * <p>Deterministic: two renders of the same {@link RunState} produce byte-identical
 * output, since every list this class walks was already ordered deterministically by
 * {@link ReportDataBuilder} (first-seen class order, cheapest-tier-first, discard reasons
 * by count with ties broken by their {@link java.util.LinkedHashMap} encounter order).
 */
public final class MarkdownReportRenderer {

    private final ReportDataBuilder dataBuilder = new ReportDataBuilder();

    public String render(RunState state) {
        return render(dataBuilder.build(state));
    }

    public String render(ReportData data) {
        StringBuilder md = new StringBuilder();
        renderHeadline(md, data);
        renderStatusCounts(md, data);
        renderTierTable(md, data);
        renderDiscardReasons(md, data);
        renderClassSections(md, data);
        renderSpringSection(md, data);
        return md.toString();
    }

    private void renderHeadline(StringBuilder md, ReportData data) {
        md.append("# JTestForge report - ").append(data.phase()).append(" run ").append(data.runId()).append("\n\n");
        md.append("- Module: `").append(data.modulePath()).append("`\n");
        md.append("- Provider: `").append(data.providerId()).append("`\n");
        md.append("- Started: ").append(data.startedAt()).append("\n");
        md.append("- Updated: ").append(data.updatedAt()).append("\n\n");

        md.append("## Headline\n\n");
        Baseline baseline = data.baseline();
        md.append("- Baseline line coverage: ").append(percent(baseline.lineCoverage())).append("\n");
        md.append("- Baseline branch coverage: ").append(percent(baseline.branchCoverage())).append("\n");
        md.append("- Newly covered this run: ").append(data.linesCoveredThisRun()).append(" line(s), ")
                .append(data.branchesCoveredThisRun()).append(" branch(es)\n");
        md.append("- Framework-semantic gaps: ").append(baseline.openFrameworkSemanticGaps())
                .append(" open at baseline, ").append(data.gapsClosedThisRun()).append(" closed this run, ")
                .append(data.gapsStillOpen()).append(" still open\n");
        if (baseline.mutationScore() != null) {
            md.append("- Baseline mutation score: ").append(percent(baseline.mutationScore())).append("\n");
        }
        md.append("- Mutants killed this run: ").append(data.mutantsKilledThisRun()).append("\n\n");
    }

    private void renderStatusCounts(StringBuilder md, ReportData data) {
        md.append("## Units\n\n");
        md.append("Total: ").append(data.totalUnits()).append(", kept: ").append(data.unitsKept()).append("\n\n");
        if (data.statusCounts().isEmpty()) {
            md.append("_No units were discovered for this run._\n\n");
            return;
        }
        md.append("| Status | Count |\n|---|---|\n");
        for (UnitStatus status : UnitStatus.values()) {
            Integer count = data.statusCounts().get(status);
            if (count != null && count > 0) {
                md.append("| ").append(status).append(" | ").append(count).append(" |\n");
            }
        }
        md.append('\n');
    }

    private void renderTierTable(StringBuilder md, ReportData data) {
        md.append("## Per-tier cost\n\n");
        if (data.tierSummaries().isEmpty()) {
            md.append("_No tier attempted any unit._\n\n");
            return;
        }
        md.append("| Tier | Attempted | Kept | Discarded | Wall-clock |\n|---|---|---|---|---|\n");
        for (ReportData.TierSummary tier : data.tierSummaries()) {
            md.append("| ").append(tier.tier()).append(" | ").append(tier.attempted()).append(" | ")
                    .append(tier.kept()).append(" | ").append(tier.discarded()).append(" | ")
                    .append(duration(tier.wallClockMillis())).append(" |\n");
        }
        md.append('\n');
    }

    private void renderDiscardReasons(StringBuilder md, ReportData data) {
        md.append("## Discard-reason breakdown\n\n");
        if (data.discardReasons().isEmpty()) {
            md.append("_Nothing was discarded this run._\n\n");
            return;
        }
        md.append("| Reason | Count |\n|---|---|\n");
        for (ReportData.DiscardReasonCount reason : data.discardReasons()) {
            md.append("| ").append(reason.category()).append(" | ").append(reason.count()).append(" |\n");
        }
        md.append('\n');
    }

    private void renderClassSections(StringBuilder md, ReportData data) {
        md.append("## Per class\n\n");
        if (data.classSummaries().isEmpty()) {
            md.append("_No class was touched this run._\n\n");
            return;
        }
        for (ReportData.ClassSummary summary : data.classSummaries()) {
            md.append("### `").append(summary.className()).append("`\n\n");
            renderBulletList(md, "Tests added", summary.testsAdded());
            renderBulletList(md, "Discarded", summary.discarded());
            renderBulletList(md, "Mutants killed", summary.mutantsKilled());
            md.append('\n');
        }
    }

    private void renderBulletList(StringBuilder md, String label, List<String> items) {
        if (items.isEmpty()) {
            md.append("- ").append(label).append(": _none_\n");
            return;
        }
        md.append("- ").append(label).append(":\n");
        for (String item : items) {
            md.append("  - ").append(item).append('\n');
        }
    }

    private void renderSpringSection(StringBuilder md, ReportData data) {
        if (data.springTiers() == null) {
            return;
        }
        md.append("## Spring context-load accounting\n\n");
        md.append("- Context loads: ").append(data.springTiers().contextLoads())
                .append(" / ").append(data.springTiers().contextLoadBudget()).append('\n');
        if (!data.springTiers().unavailable().isEmpty()) {
            md.append("- Unavailable tiers:\n");
            for (var entry : data.springTiers().unavailable().entrySet()) {
                md.append("  - ").append(entry.getKey()).append(": ").append(entry.getValue()).append('\n');
            }
        }
        md.append('\n');
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
}
