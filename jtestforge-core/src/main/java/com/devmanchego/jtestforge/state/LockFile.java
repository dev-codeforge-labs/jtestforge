package com.devmanchego.jtestforge.state;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.util.Objects;

/**
 * Module-level exclusion between concurrent JTestForge runs —
 * jtestforge-specification.md §8.3.
 *
 * <p>Created with {@link StandardOpenOption#CREATE_NEW} so acquisition is atomic at the
 * filesystem level: two processes racing for it cannot both believe they hold it. The
 * file records the holder's PID and start time, which is what lets a later run
 * distinguish a genuinely running sibling from a lock orphaned by a killed process.
 *
 * <p>A stale lock is reported but <b>never cleared automatically</b>. Auto-clearing would
 * defeat the lock in exactly the case where it matters most - a machine that has recycled
 * the recorded PID onto an unrelated live process - so clearing is an explicit user
 * action via {@code --force-unlock}.
 */
public final class LockFile implements AutoCloseable {

    public static final String LOCK_FILE_NAME = "jtestforge.lock";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long UNKNOWN_PID = -1L;

    private final Path lockPath;

    private LockFile(Path lockPath) {
        this.lockPath = lockPath;
    }

    /**
     * Acquires the lock for the current process.
     *
     * @throws LockHeldException if another run holds it, or a stale lock remains
     */
    public static LockFile acquire(Path stateDir, Clock clock) {
        Objects.requireNonNull(stateDir, "stateDir");
        Objects.requireNonNull(clock, "clock");
        Path lockPath = stateDir.resolve(LOCK_FILE_NAME);

        String content = """
                {"pid": %d, "acquiredAt": "%s"}
                """.formatted(ProcessHandle.current().pid(), clock.instant());

        try {
            Files.createDirectories(stateDir);
            Files.writeString(lockPath, content, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            return new LockFile(lockPath);
        } catch (FileAlreadyExistsException e) {
            throw describeHolder(lockPath);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to acquire lock at " + lockPath, e);
        }
    }

    /**
     * Removes a lock file regardless of who holds it — backs {@code --force-unlock}.
     *
     * @return whether there was a lock file to remove
     */
    public static boolean forceUnlock(Path stateDir) {
        Path lockPath = stateDir.resolve(LOCK_FILE_NAME);
        try {
            return Files.deleteIfExists(lockPath);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to remove lock at " + lockPath, e);
        }
    }

    public Path lockPath() {
        return lockPath;
    }

    /** Releases the lock. Idempotent: releasing an already-released lock does nothing. */
    public void release() {
        try {
            Files.deleteIfExists(lockPath);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to release lock at " + lockPath, e);
        }
    }

    @Override
    public void close() {
        release();
    }

    private static LockHeldException describeHolder(Path lockPath) {
        long holderPid = readHolderPid(lockPath);

        if (holderPid == UNKNOWN_PID) {
            // The lock exists but says nothing usable. Blocking is the only safe reading:
            // an unreadable lock is not evidence that nobody holds it.
            return new LockHeldException(UNKNOWN_PID, false,
                    ("Another JTestForge run appears to hold %s, but the lock file could not "
                            + "be read. If you are certain no other run is active, clear it with "
                            + "--force-unlock.").formatted(lockPath));
        }

        boolean holderAlive = ProcessHandle.of(holderPid)
                .map(ProcessHandle::isAlive)
                .orElse(false);

        if (holderAlive) {
            return new LockHeldException(holderPid, false,
                    ("Another JTestForge run (PID %d) is already working on this module. "
                            + "Wait for it to finish, or run against a different module.")
                            .formatted(holderPid));
        }
        return new LockHeldException(holderPid, true,
                ("A lock left by PID %d remains at %s, but that process is no longer alive. "
                        + "If no other run is active, clear it with --force-unlock.")
                        .formatted(holderPid, lockPath));
    }

    private static long readHolderPid(Path lockPath) {
        try {
            JsonNode tree = MAPPER.readTree(Files.readString(lockPath));
            JsonNode pidNode = tree.get("pid");
            return pidNode != null && pidNode.canConvertToLong() ? pidNode.longValue() : UNKNOWN_PID;
        } catch (IOException | RuntimeException e) {
            return UNKNOWN_PID;
        }
    }
}
