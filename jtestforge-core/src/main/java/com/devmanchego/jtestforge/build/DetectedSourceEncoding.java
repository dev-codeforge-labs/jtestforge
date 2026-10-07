package com.devmanchego.jtestforge.build;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * The character encoding of the target module's sources, and where that was learned from.
 *
 * @param source what decided it, for the run's notes (e.g. {@code "configured"} or
 *               {@code "pom.xml project.build.sourceEncoding = ISO-8859-1"})
 * @param declared whether the module actually says so; {@code false} for the UTF-8 assumed when
 *                 nothing in its pom chain does
 */
public record DetectedSourceEncoding(Charset charset, String source, boolean declared) {

    public DetectedSourceEncoding(Charset charset, String source) {
        this(charset, source, true);
    }

    /** What is used when no pom in the chain declares an encoding. */
    public static DetectedSourceEncoding assumedUtf8() {
        return new DetectedSourceEncoding(StandardCharsets.UTF_8,
                "no encoding declared in the pom chain - assuming UTF-8", false);
    }
}
