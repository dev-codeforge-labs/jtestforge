package com.devmanchego.jtestforge.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Computes a stable hash of a bound {@link JTestForgeConfig}, used by the run state
 * (jtestforge-specification.md §8) to detect that the configuration changed between
 * runs and invalidate the baseline accordingly.
 *
 * <p>Hashing the bound, defaulted config rather than the raw YAML text means two files
 * that differ only in comments, key order, or an explicitly-written default produce the
 * same hash - only semantic changes invalidate a run.
 */
public final class ConfigHasher {

    private static final ObjectMapper CANONICAL_MAPPER = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private ConfigHasher() {
    }

    /** Returns {@code "sha256:<hex digest>"}, matching the format used in state.json. */
    public static String hash(JTestForgeConfig config) {
        try {
            byte[] canonicalJson = CANONICAL_MAPPER.writeValueAsBytes(config);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(canonicalJson);
            return "sha256:" + HexFormat.of().formatHex(hashBytes);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is a mandatory JDK algorithm (JLS platform requirement); this is
            // unreachable on any conforming JVM.
            throw new IllegalStateException("SHA-256 unavailable on this JVM", e);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }
}
