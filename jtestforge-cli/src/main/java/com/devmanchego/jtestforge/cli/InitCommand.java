package com.devmanchego.jtestforge.cli;

import com.devmanchego.jtestforge.config.PromptsConfig;
import com.devmanchego.jtestforge.prompt.PromptTemplateId;
import com.devmanchego.jtestforge.prompt.PromptTemplateLoader;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;

/**
 * {@code jtestforge init} — jtestforge-specification.md §14: scaffolds
 * {@code jtestforge.yaml} and the eleven bundled prompt/rules files a fresh config points
 * at by default.
 *
 * <p>Never overwrites a file that already exists, config or template alike. A second
 * {@code init} against an already-scaffolded module must be a safe no-op for anything the
 * user (or a generated run) has since edited - a config with real values, or a prompt
 * template tuned after watching a few real generations.
 */
@Command(
        name = "init",
        description = "Scaffold jtestforge.yaml and the default prompt templates."
)
public final class InitCommand implements Callable<Integer> {

    private static final String BUNDLED_CONFIG_TEMPLATE = "jtestforge.yaml.template";

    @Mixin
    private CommonModuleOptions options;

    @Spec
    private CommandSpec spec;

    @Override
    public Integer call() {
        ConsoleOutput console = console();
        Path configPath = targetConfigPath();

        boolean configWritten = writeIfAbsent(configPath, InitCommand::bundledConfigTemplate, console);
        console.info(configWritten
                ? "Wrote " + configPath
                : configPath + " already exists; left untouched.");

        Path promptsBaseDir = configPath.toAbsolutePath().getParent();
        PromptTemplateLoader templateLoader = new PromptTemplateLoader();
        PromptsConfig defaults = new PromptsConfig(null, null, null, null, null, null, null, null, null, null, null);

        int written = 0;
        for (PromptTemplateId id : PromptTemplateId.values()) {
            Path templatePath = resolveAgainst(promptsBaseDir, id.configuredPath(defaults));
            boolean wroteThisOne = writeIfAbsent(templatePath, () -> templateLoader.bundledTextOf(id), console);
            if (wroteThisOne) {
                written++;
            }
        }
        console.info(written + " of " + PromptTemplateId.values().length + " prompt/rules file(s) written; "
                + "the rest already existed and were left untouched.");
        return ExitCodes.SUCCESS;
    }

    private Path targetConfigPath() {
        if (options.config != null) {
            return options.config;
        }
        if (options.module != null) {
            return options.module.resolve("jtestforge.yaml");
        }
        return Path.of("jtestforge.yaml");
    }

    private Path resolveAgainst(Path baseDir, String configuredPath) {
        Path path = Path.of(configuredPath);
        return path.isAbsolute() || baseDir == null ? path : baseDir.resolve(path);
    }

    /** @return whether the file was actually written */
    private boolean writeIfAbsent(Path target, java.util.function.Supplier<String> content, ConsoleOutput console) {
        if (Files.exists(target)) {
            return false;
        }
        try {
            if (target.getParent() != null) {
                Files.createDirectories(target.getParent());
            }
            Files.writeString(target, content.get(), StandardCharsets.UTF_8);
            return true;
        } catch (IOException e) {
            console.error("Failed to write " + target + ": " + e.getMessage());
            throw new UncheckedIOException(e);
        }
    }

    private static String bundledConfigTemplate() {
        try (InputStream in = InitCommand.class.getClassLoader().getResourceAsStream(BUNDLED_CONFIG_TEMPLATE)) {
            if (in == null) {
                throw new IllegalStateException("Bundled config template is missing from the jar: "
                        + BUNDLED_CONFIG_TEMPLATE);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private ConsoleOutput console() {
        return new ConsoleOutput(spec.commandLine().getOut(), spec.commandLine().getErr());
    }
}
