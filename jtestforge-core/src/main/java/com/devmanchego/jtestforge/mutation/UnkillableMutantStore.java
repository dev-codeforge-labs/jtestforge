package com.devmanchego.jtestforge.mutation;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The persistent list of mutants proven unkillable — jtestforge-specification.md §10.1
 * step 6, {@code harden.unkillableMutantsFile}.
 *
 * <p>Equivalent mutants exist and are undecidable in general. Without this list, a mutant
 * that reaches {@code maxAttemptsPerMutant} without dying would be re-attempted, and re-
 * fail, on every future {@code harden} run - burning tokens on the same unkillable mutant
 * forever. Recording it once and never retrying it is the entire point.
 *
 * <p>Append-only by design: entries are never rewritten or removed by this class (a
 * developer who disagrees with an entry can always hand-edit the file, since it is a
 * plain, human-readable text file - not a reason for this class to expose an API to do the
 * same). One line per entry, tab-separated: {@code <mutantId>\t<reason>}.
 */
public final class UnkillableMutantStore {

    private final Path file;

    public UnkillableMutantStore(Path file) {
        this.file = Objects.requireNonNull(file, "file");
    }

    /** Every recorded entry, in the order they were appended. */
    public List<UnkillableEntry> load() {
        if (!Files.isRegularFile(file)) {
            return List.of();
        }
        List<UnkillableEntry> entries = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isBlank()) {
                    continue;
                }
                int tab = line.indexOf('\t');
                entries.add(tab < 0
                        ? new UnkillableEntry(line, "")
                        : new UnkillableEntry(line.substring(0, tab), line.substring(tab + 1)));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read " + file, e);
        }
        return List.copyOf(entries);
    }

    /** The ids alone, for the §10.1 step 4 filter. */
    public Set<String> loadIds() {
        Set<String> ids = new LinkedHashSet<>();
        for (UnkillableEntry entry : load()) {
            ids.add(entry.mutantId());
        }
        return Set.copyOf(ids);
    }

    public boolean contains(String mutantId) {
        return loadIds().contains(mutantId);
    }

    /**
     * Appends one entry. Not deduplicated on write: the caller (the {@code harden} loop)
     * only ever reaches this once a mutant has already exhausted {@code maxAttemptsPerMutant}
     * without dying, so a genuine duplicate would mean it was attempted again after
     * already being unkillable - a bug in the caller's own {@code loadIds()} filtering,
     * not something this class should paper over by silently deduplicating.
     */
    public void append(String mutantId, String reason) {
        String sanitizedReason = reason.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
        String line = mutantId + "\t" + sanitizedReason + System.lineSeparator();
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            Files.writeString(file, line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to append to " + file, e);
        }
    }

    public record UnkillableEntry(String mutantId, String reason) {
    }
}
