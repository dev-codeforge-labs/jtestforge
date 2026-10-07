package com.devmanchego.jtestforge.build;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Works out which Java release the target module's code is written for, without running
 * Maven or a compiler. An explicit setting wins; otherwise the module's pom and its
 * parents (on disk via {@code relativePath}, else in the local repository) are read for
 * the usual compiler settings; failing that, the class-file version of what the module
 * last built ({@code target/classes}, then {@code target/*.jar}).
 */
public final class JavaVersionDetector {

    private static final int MAX_CLASS_SAMPLES = 50;
    private static final int CLASS_FILE_MAGIC = 0xCAFEBABE;

    private final PomChain pomChain;

    /** @param localRepository where parent poms not found on disk are looked up; may be null */
    public JavaVersionDetector(MavenLocalRepository localRepository) {
        this.pomChain = new PomChain(localRepository);
    }

    /** @throws IllegalArgumentException if {@code configuredVersion} is set but is not a Java version */
    public DetectedJavaVersion detect(Path modulePath, String configuredVersion) {
        if (configuredVersion != null && !configuredVersion.isBlank()) {
            int release = JavaRelease.parse(configuredVersion);
            if (release == 0) {
                throw new IllegalArgumentException("Not a Java version: \"" + configuredVersion
                        + "\" (expected e.g. 8, 1.8, 11, 17)");
            }
            return new DetectedJavaVersion(release, "configured");
        }
        Path target = modulePath.resolve("target");
        return fromPom(modulePath.resolve("pom.xml"))
                .or(() -> fromClassFiles(target.resolve("classes")))
                .or(() -> fromBuiltJars(target))
                .orElse(DetectedJavaVersion.unknown());
    }

    /** The feature release of a JDK installation, read from its {@code release} file; 0 if unknown. */
    public static int javaHomeRelease(Path javaHome) {
        Path releaseFile = javaHome.resolve("release");
        if (!Files.isRegularFile(releaseFile)) {
            return 0;
        }
        try {
            for (String line : Files.readAllLines(releaseFile, StandardCharsets.ISO_8859_1)) {
                if (line.startsWith("JAVA_VERSION=")) {
                    return JavaRelease.parse(line.substring("JAVA_VERSION=".length()).replace("\"", ""));
                }
            }
        } catch (IOException e) {
            return 0;
        }
        return 0;
    }

    private Optional<DetectedJavaVersion> fromPom(Path pomFile) {
        if (!Files.isRegularFile(pomFile)) {
            return Optional.empty();
        }
        List<PomInfo> chain = pomChain.read(pomFile);
        Map<String, String> properties = PomChain.properties(chain);
        Map<String, String> compiler = PomChain.compilerConfiguration(chain);
        List<Setting> settingsInPrecedenceOrder = List.of(
                new Setting("maven-compiler-plugin <release>", compiler.get("release")),
                new Setting("maven.compiler.release", properties.get("maven.compiler.release")),
                new Setting("maven-compiler-plugin <source>", compiler.get("source")),
                new Setting("maven.compiler.source", properties.get("maven.compiler.source")),
                new Setting("maven-compiler-plugin <target>", compiler.get("target")),
                new Setting("maven.compiler.target", properties.get("maven.compiler.target")),
                new Setting("java.version", properties.get("java.version")));
        for (Setting setting : settingsInPrecedenceOrder) {
            if (setting.rawValue() == null) {
                continue;
            }
            String value = PomChain.interpolate(setting.rawValue(), properties);
            int release = JavaRelease.parse(value);
            if (release > 0) {
                return Optional.of(new DetectedJavaVersion(release, "pom.xml " + setting.name() + " = " + value));
            }
        }
        return Optional.empty();
    }

    private static Optional<DetectedJavaVersion> fromClassFiles(Path classesDirectory) {
        if (!Files.isDirectory(classesDirectory)) {
            return Optional.empty();
        }
        try (Stream<Path> walk = Files.walk(classesDirectory)) {
            int major = walk.filter(path -> path.toString().endsWith(".class"))
                    .limit(MAX_CLASS_SAMPLES)
                    .mapToInt(JavaVersionDetector::majorOfClassFile)
                    .max().orElse(0);
            return fromMajor(major, "bytecode in target/classes");
        } catch (IOException | UncheckedIOException e) {
            return Optional.empty();
        }
    }

    private static Optional<DetectedJavaVersion> fromBuiltJars(Path targetDirectory) {
        if (!Files.isDirectory(targetDirectory)) {
            return Optional.empty();
        }
        List<Path> jars;
        try (Stream<Path> list = Files.list(targetDirectory)) {
            jars = list.filter(path -> {
                String name = path.getFileName().toString();
                return name.endsWith(".jar") && !name.endsWith("-sources.jar") && !name.endsWith("-javadoc.jar");
            }).sorted().toList();
        } catch (IOException | UncheckedIOException e) {
            return Optional.empty();
        }
        for (Path jar : jars) {
            Optional<DetectedJavaVersion> found = fromMajor(majorInJar(jar), "bytecode in target/" + jar.getFileName());
            if (found.isPresent()) {
                return found;
            }
        }
        return Optional.empty();
    }

    /** Multi-release entries under {@code META-INF/} are skipped: they target a newer JDK by design. */
    private static int majorInJar(Path jar) {
        int max = 0;
        int sampled = 0;
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements() && sampled < MAX_CLASS_SAMPLES) {
                ZipEntry entry = entries.nextElement();
                if (!entry.getName().endsWith(".class") || entry.getName().startsWith("META-INF/")) {
                    continue;
                }
                try (InputStream in = zip.getInputStream(entry)) {
                    max = Math.max(max, majorOfHeader(in.readNBytes(8)));
                }
                sampled++;
            }
        } catch (IOException e) {
            return max;
        }
        return max;
    }

    private static int majorOfClassFile(Path classFile) {
        try (InputStream in = Files.newInputStream(classFile)) {
            return majorOfHeader(in.readNBytes(8));
        } catch (IOException e) {
            return 0;
        }
    }

    private static int majorOfHeader(byte[] header) {
        if (header.length < 8) {
            return 0;
        }
        int magic = ((header[0] & 0xff) << 24) | ((header[1] & 0xff) << 16)
                | ((header[2] & 0xff) << 8) | (header[3] & 0xff);
        return magic == CLASS_FILE_MAGIC ? ((header[6] & 0xff) << 8) | (header[7] & 0xff) : 0;
    }

    private static Optional<DetectedJavaVersion> fromMajor(int major, String where) {
        int release = JavaRelease.fromClassFileMajor(major);
        return release > 0
                ? Optional.of(new DetectedJavaVersion(release, where + " (class file version " + major + ")"))
                : Optional.empty();
    }

    private record Setting(String name, String rawValue) {
    }
}
