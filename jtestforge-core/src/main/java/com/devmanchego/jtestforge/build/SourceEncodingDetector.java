package com.devmanchego.jtestforge.build;

import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Works out which character encoding the target module's Java sources are written in,
 * without running Maven: an explicit setting wins; otherwise the module's pom and its parents
 * are read for the settings Maven's own compiler plugin honours.
 *
 * <p>Needed because everything JTestForge reads or writes - the test class it merges into, the
 * production class it puts in a prompt - has to round-trip byte for byte in the developer's own
 * encoding. A legacy module saved as ISO-8859-1 or windows-1252 with accented comments is not
 * valid UTF-8, and treating it as such either refuses the file or, worse, rewrites its
 * accents as replacement characters.
 */
public final class SourceEncodingDetector {

    private final PomChain pomChain;

    /** @param localRepository where parent poms not found on disk are looked up; may be null */
    public SourceEncodingDetector(MavenLocalRepository localRepository) {
        this.pomChain = new PomChain(localRepository);
    }

    /**
     * @param configuredEncoding {@code project.sourceEncoding}; {@code null} or blank to detect
     * @throws IllegalArgumentException if {@code configuredEncoding} is set but names no charset
     *                                  this JVM supports
     */
    public DetectedSourceEncoding detect(Path modulePath, String configuredEncoding) {
        if (configuredEncoding != null && !configuredEncoding.isBlank()) {
            return new DetectedSourceEncoding(parseOrThrow(configuredEncoding), "configured");
        }
        return fromPom(modulePath.resolve("pom.xml")).orElse(DetectedSourceEncoding.assumedUtf8());
    }

    /**
     * Precedence follows Maven: the compiler plugin's own {@code <encoding>}, then the
     * {@code maven.compiler.encoding} property that plugin reads, then the reporting-wide
     * {@code project.build.sourceEncoding}. A value that is not a charset is skipped rather
     * than trusted, so a typo in a pom falls through to the next source instead of failing.
     */
    private Optional<DetectedSourceEncoding> fromPom(Path pomFile) {
        if (!Files.isRegularFile(pomFile)) {
            return Optional.empty();
        }
        List<PomInfo> chain = pomChain.read(pomFile);
        Map<String, String> properties = PomChain.properties(chain);
        Map<String, String> compiler = PomChain.compilerConfiguration(chain);

        List<Source> sourcesInPrecedenceOrder = List.of(
                new Source("maven-compiler-plugin <encoding>", compiler.get("encoding")),
                new Source("maven.compiler.encoding", properties.get("maven.compiler.encoding")),
                new Source("project.build.sourceEncoding", properties.get("project.build.sourceEncoding")));
        for (Source source : sourcesInPrecedenceOrder) {
            if (source.rawValue() == null) {
                continue;
            }
            String value = PomChain.interpolate(source.rawValue(), properties).strip();
            Optional<Charset> charset = parse(value);
            if (charset.isPresent()) {
                return Optional.of(new DetectedSourceEncoding(charset.get(), "pom.xml " + source.name() + " = " + value));
            }
        }
        return Optional.empty();
    }

    private static Charset parseOrThrow(String name) {
        return parse(name.strip()).orElseThrow(() -> new IllegalArgumentException(
                "Not a supported character encoding: \"" + name + "\" (expected e.g. UTF-8, ISO-8859-1, windows-1252)"));
    }

    private static Optional<Charset> parse(String name) {
        try {
            return Optional.of(Charset.forName(name));
        } catch (IllegalArgumentException e) {
            // IllegalCharsetNameException and UnsupportedCharsetException are both subclasses of it.
            return Optional.empty();
        }
    }

    private record Source(String name, String rawValue) {
    }
}
