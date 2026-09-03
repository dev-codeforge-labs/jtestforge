package com.devmanchego.jtestforge.cli;

import com.devmanchego.jtestforge.config.ConfigLoadException;
import com.devmanchego.jtestforge.config.JTestForgeConfig;
import com.devmanchego.jtestforge.model.RunState;
import com.devmanchego.jtestforge.report.HtmlReportRenderer;
import com.devmanchego.jtestforge.report.MarkdownReportRenderer;
import com.devmanchego.jtestforge.state.StateStore;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * {@code jtestforge report} — jtestforge-specification.md §15. Renders the last run's
 * {@code state.json} - never a second source of truth - as Markdown, written to
 * {@code <stateDir>/report-<runId>.md} and also printed to stdout; {@code --html}
 * additionally writes a self-contained {@code report-<runId>.html} alongside it.
 */
@Command(
        name = "report",
        description = "Render the Markdown/HTML report for the last run."
)
public final class ReportCommand implements Callable<Integer> {

    @Mixin
    private CommonModuleOptions options;

    @Option(names = "--html", description = "Also write a self-contained HTML report")
    private boolean html;

    @Spec
    private CommandSpec spec;

    @Override
    public Integer call() {
        ConsoleOutput console = console();
        JTestForgeConfig config;
        try {
            config = ConfigResolver.load(options).config();
        } catch (ConfigLoadException e) {
            console.error(e.getMessage());
            return ExitCodes.CONFIGURATION_OR_PREFLIGHT_ERROR;
        }

        Path modulePath = ConfigResolver.modulePathOf(config);
        Path stateDir = ConfigResolver.stateDirOf(config, modulePath);
        StateStore stateStore = new StateStore(stateDir, Clock.systemUTC());

        Optional<RunState> loaded = stateStore.load();
        if (loaded.isEmpty()) {
            console.info("No run state found at " + stateStore.stateFile() + ". Nothing to report.");
            return ExitCodes.SUCCESS;
        }
        RunState state = loaded.get();

        String markdown = new MarkdownReportRenderer().render(state);
        Path markdownPath = stateDir.resolve("report-" + state.runId() + ".md");
        writeFile(markdownPath, markdown);
        console.info("Wrote " + markdownPath);

        if (html) {
            String htmlContent = new HtmlReportRenderer().render(state);
            Path htmlPath = stateDir.resolve("report-" + state.runId() + ".html");
            writeFile(htmlPath, htmlContent);
            console.info("Wrote " + htmlPath);
        }

        console.info("");
        console.info(markdown);
        return ExitCodes.SUCCESS;
    }

    private void writeFile(Path path, String content) {
        try {
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            Files.writeString(path, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write " + path, e);
        }
    }

    private ConsoleOutput console() {
        return new ConsoleOutput(spec.commandLine().getOut(), spec.commandLine().getErr());
    }
}
