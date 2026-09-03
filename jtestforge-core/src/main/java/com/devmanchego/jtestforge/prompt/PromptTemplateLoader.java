package com.devmanchego.jtestforge.prompt;

import com.devmanchego.jtestforge.config.PromptsConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Loads and validates every prompt template at startup —
 * jtestforge-implementation-plan.md phase 10.
 *
 * <p>Templates live as files in the user's project so they can be versioned, edited and
 * A/B compared without recompiling (§5). The bundled copies inside the jar are the
 * defaults {@code jtestforge init} scaffolds out; {@link #loadBundled} reads those
 * directly, which is what makes the tool usable before anyone has customised anything.
 *
 * <p>Validation is deliberately split in two, matching how bad each problem actually is:
 * an <b>unknown</b> placeholder is an error that stops startup, because the token would
 * otherwise render literally into a prompt and go unnoticed; a vocabulary entry that
 * <b>no</b> template uses is only a warning, because it costs nothing at runtime and is
 * usually a template being deliberately simplified rather than a mistake.
 */
public final class PromptTemplateLoader {

    private static final Logger LOGGER = LoggerFactory.getLogger(PromptTemplateLoader.class);

    /**
     * Reads the defaults bundled in the jar. Used when no config has been scaffolded
     * yet, and as the source {@code jtestforge init} copies from.
     */
    public Map<PromptTemplateId, PromptTemplate> loadBundled() {
        Map<PromptTemplateId, PromptTemplate> templates = new EnumMap<>(PromptTemplateId.class);
        for (PromptTemplateId id : PromptTemplateId.values()) {
            templates.put(id, new PromptTemplate(id, readBundled(id), "bundled:" + id.bundledResourcePath()));
        }
        warnAboutUnusedPlaceholders(templates);
        return Map.copyOf(templates);
    }

    /**
     * Reads the user's own templates from the paths their config names, resolved against
     * {@code baseDir} (the directory holding {@code jtestforge.yaml}).
     */
    public Map<PromptTemplateId, PromptTemplate> load(PromptsConfig prompts, Path baseDir) {
        Map<PromptTemplateId, PromptTemplate> templates = new EnumMap<>(PromptTemplateId.class);
        for (PromptTemplateId id : PromptTemplateId.values()) {
            Path file = resolve(baseDir, id.configuredPath(prompts));
            templates.put(id, new PromptTemplate(id, readFile(file), file.toString()));
        }
        warnAboutUnusedPlaceholders(templates);
        return Map.copyOf(templates);
    }

    /** The default text of one template, for {@code jtestforge init} to write out. */
    public String bundledTextOf(PromptTemplateId id) {
        return readBundled(id);
    }

    private void warnAboutUnusedPlaceholders(Map<PromptTemplateId, PromptTemplate> templates) {
        Set<PromptPlaceholder> used = EnumSet.noneOf(PromptPlaceholder.class);
        templates.values().forEach(template -> used.addAll(template.referencedPlaceholders()));

        List<PromptPlaceholder> unused = new ArrayList<>();
        for (PromptPlaceholder placeholder : PromptPlaceholder.values()) {
            if (!used.contains(placeholder)) {
                unused.add(placeholder);
            }
        }
        if (!unused.isEmpty()) {
            LOGGER.warn("No prompt template references these placeholders, so the context "
                    + "assembled for them is never sent to the model: {}", unused);
        }
    }

    private Path resolve(Path baseDir, String configuredPath) {
        Path path = Path.of(configuredPath);
        return path.isAbsolute() || baseDir == null ? path : baseDir.resolve(path);
    }

    private String readBundled(PromptTemplateId id) {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(id.bundledResourcePath())) {
            if (in == null) {
                throw new TemplateValidationException(
                        "Bundled prompt template is missing from the jar: " + id.bundledResourcePath());
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read bundled template " + id.bundledResourcePath(), e);
        }
    }

    private String readFile(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new TemplateValidationException("Failed to read prompt template " + file, e);
        }
    }
}
