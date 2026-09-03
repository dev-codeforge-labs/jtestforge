package com.devmanchego.jtestforge.state;

import com.devmanchego.jtestforge.model.RunState;
import com.devmanchego.jtestforge.model.UnitStatus;
import com.devmanchego.jtestforge.model.WorkUnit;
import com.devmanchego.jtestforge.model.WorkUnitId;
import com.devmanchego.jtestforge.util.AtomicFileWriter;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Objects;
import java.util.Optional;

/**
 * Reads and writes {@code <stateDir>/state.json} — jtestforge-specification.md §8.2.
 *
 * <p>Three properties are non-negotiable and are this class's whole reason to exist:
 *
 * <ol>
 *   <li><b>Write-ahead.</b> {@link #markInProgress} persists before the AI is invoked, so
 *       a process killed mid-unit is distinguishable from one that never started it.</li>
 *   <li><b>Atomic writes.</b> Every save goes through {@link AtomicFileWriter}, so a kill
 *       during a write can never leave truncated JSON.</li>
 *   <li><b>Corruption is loud.</b> An unreadable state file stops the run instead of
 *       being mistaken for a fresh start - see {@link StateCorruptException}.</li>
 * </ol>
 */
public final class StateStore {

    public static final String STATE_FILE_NAME = "state.json";

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private final Path stateFile;
    private final Clock clock;

    public StateStore(Path stateDir, Clock clock) {
        this.stateFile = Objects.requireNonNull(stateDir, "stateDir").resolve(STATE_FILE_NAME);
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public Path stateFile() {
        return stateFile;
    }

    /**
     * Loads the existing run state, or empty if this module has no state file yet.
     *
     * @throws StateCorruptException if a file exists but is unparseable or has an
     *                               unsupported schema version
     */
    public Optional<RunState> load() {
        if (!Files.isRegularFile(stateFile)) {
            return Optional.empty();
        }

        String content;
        try {
            content = Files.readString(stateFile);
        } catch (IOException e) {
            throw new StateCorruptException(stateFile,
                    "Run state file could not be read: " + stateFile, e);
        }

        JsonNode tree;
        try {
            tree = MAPPER.readTree(content);
        } catch (JsonProcessingException e) {
            throw new StateCorruptException(stateFile,
                    "Run state file is not valid JSON (a previous run was probably killed "
                            + "mid-write): " + stateFile, e);
        }

        requireSupportedSchemaVersion(tree);

        try {
            return Optional.of(MAPPER.treeToValue(tree, RunState.class));
        } catch (JsonProcessingException e) {
            throw new StateCorruptException(stateFile,
                    "Run state file does not match the expected structure: " + stateFile, e);
        }
    }

    /** Persists {@code state} atomically, stamping {@code updatedAt} from the clock. */
    public RunState save(RunState state) {
        Objects.requireNonNull(state, "state");
        RunState stamped = state.withUpdatedAt(clock.instant());
        try {
            AtomicFileWriter.write(stateFile,
                    MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(stamped));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write run state to " + stateFile, e);
        }
        return stamped;
    }

    /**
     * Write-ahead marker (§8.2.1): records the unit as {@link UnitStatus#IN_PROGRESS} and
     * increments its attempt count, persisting <em>before</em> the caller invokes the AI.
     *
     * <p>The persistence is the entire point. Deferring it to a later batched save would
     * make a process killed mid-unit indistinguishable from one that never started the
     * unit, and resume would have to guess.
     */
    public RunState markInProgress(RunState state, WorkUnitId id) {
        WorkUnit unit = state.unit(id).orElseThrow(
                () -> new IllegalArgumentException("No such work unit in this run: " + id));
        WorkUnit marked = unit
                .withStatus(UnitStatus.IN_PROGRESS)
                .withAttempts(unit.attempts() + 1);
        return save(state.withUnit(marked));
    }

    /** Applies a change to one unit and persists the result. */
    public RunState updateUnit(RunState state, WorkUnit updated) {
        return save(state.withUnit(updated));
    }

    private void requireSupportedSchemaVersion(JsonNode tree) {
        JsonNode versionNode = tree.get("schemaVersion");
        if (versionNode == null || !versionNode.isInt()) {
            throw new StateCorruptException(stateFile,
                    "Run state file has no schemaVersion: " + stateFile);
        }
        int version = versionNode.intValue();
        if (version != RunState.CURRENT_SCHEMA_VERSION) {
            throw new StateCorruptException(stateFile,
                    "Run state file has unsupported schemaVersion %d (this build understands %d): %s"
                            .formatted(version, RunState.CURRENT_SCHEMA_VERSION, stateFile));
        }
    }
}
