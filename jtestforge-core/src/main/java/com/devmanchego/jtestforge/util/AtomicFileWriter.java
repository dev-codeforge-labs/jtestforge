package com.devmanchego.jtestforge.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.CopyOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;

/**
 * Writes a file's full content by writing to a sibling temp file and then moving it
 * into place, so a reader (or a crash of this process) never observes a partially
 * written file.
 *
 * <p>Used for every file JTestForge writes that must never be seen half-written: the
 * run state (state.json — see jtestforge-specification.md §8.2), the config hash cache,
 * and generated/merged test-class sources. A {@code Ctrl+C} or a JVM crash mid-write
 * must never leave a truncated file that costs the whole run.
 */
public final class AtomicFileWriter {

    private AtomicFileWriter() {
    }

    /**
     * Writes {@code content} to {@code target} atomically.
     *
     * <p>The temp file is created in the same directory as {@code target} so the final
     * move is a same-filesystem rename, which is what makes it atomic. If the platform's
     * {@code ATOMIC_MOVE} is unsupported for that pair of paths (observed on some
     * network drives and older Windows filesystem drivers), falls back to a plain
     * replace-move: still safe against a half-written file, since the temp file is
     * always fully written and flushed before the move starts, but not guaranteed
     * atomic if another process is renaming into the same target concurrently.
     */
    public static void write(Path target, String content) throws IOException {
        write(target, content, StandardCharsets.UTF_8);
    }

    /**
     * As {@link #write(Path, String)}, in {@code charset} - for a developer's own source files,
     * which must be written back in the encoding they were read in. Throws if {@code content} has
     * a character {@code charset} cannot represent, rather than writing a replacement character.
     */
    public static void write(Path target, String content, java.nio.charset.Charset charset) throws IOException {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(charset, "charset");

        Path parent = target.toAbsolutePath().getParent();
        if (parent == null) {
            throw new IOException("Target path has no parent directory: " + target);
        }
        Files.createDirectories(parent);

        Path tempFile = Files.createTempFile(parent, target.getFileName().toString(), ".tmp");
        try {
            // String.getBytes would silently turn an unmappable character into a question mark;
            // the encoder reports it instead.
            java.nio.ByteBuffer encoded = charset.newEncoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .encode(java.nio.CharBuffer.wrap(content));
            byte[] bytes = new byte[encoded.remaining()];
            encoded.get(bytes);
            // Forced to disk before the rename: without it a power cut can leave the rename
            // durable and the content not, i.e. an empty or truncated state.json or test class.
            try (java.nio.channels.FileChannel channel = java.nio.channels.FileChannel.open(tempFile,
                    java.nio.file.StandardOpenOption.WRITE)) {
                channel.write(java.nio.ByteBuffer.wrap(bytes));
                channel.force(true);
            }
            moveIntoPlace(tempFile, target);
        } finally {
            // If the move succeeded, tempFile no longer exists at this path and this is
            // a no-op. If anything above failed, this cleans up rather than leaving a
            // stray .tmp file behind.
            Files.deleteIfExists(tempFile);
        }
    }

    private static void moveIntoPlace(Path tempFile, Path target) throws IOException {
        try {
            Files.move(tempFile, target,
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(tempFile, target, (CopyOption) StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
