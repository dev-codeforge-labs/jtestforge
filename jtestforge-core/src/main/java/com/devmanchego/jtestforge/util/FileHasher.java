package com.devmanchego.jtestforge.util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Content hashes of source files, in the {@code "sha256:<hex>"} form used throughout
 * state.json.
 *
 * <p>These hashes are what make resume reconciliation possible (jtestforge-specification.md
 * §8.2.3): comparing a recorded hash against the file on disk is how a resumed run tells
 * "this unit's recorded outcome is still valid" from "the file changed underneath us".
 */
public final class FileHasher {

    private static final int BUFFER_SIZE = 8192;

    private FileHasher() {
    }

    /**
     * Hashes the file's bytes, or returns empty if the file does not exist.
     *
     * <p>A missing file is a normal, meaningful outcome here - a test class that has not
     * been created yet, or a production class deleted between runs - so it is expressed
     * as an empty result rather than an exception.
     */
    public static Optional<String> hash(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        MessageDigest digest = sha256();
        try (InputStream in = Files.newInputStream(file);
             DigestInputStream digestStream = new DigestInputStream(in, digest)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            while (digestStream.read(buffer) != -1) {
                // DigestInputStream updates the digest as a side effect of reading.
            }
        }
        return Optional.of("sha256:" + HexFormat.of().formatHex(digest.digest()));
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable on this JVM", e);
        }
    }
}
