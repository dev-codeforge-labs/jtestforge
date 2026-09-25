package com.devmanchego.jtestforge.cli;

import com.devmanchego.jtestforge.build.DependencyTreeResolver;
import com.devmanchego.jtestforge.build.DetectedJavaVersion;
import com.devmanchego.jtestforge.build.JavaVersionDetector;
import com.devmanchego.jtestforge.build.MavenClasspathResolver;
import com.devmanchego.jtestforge.build.MavenLocalRepository;
import com.devmanchego.jtestforge.build.ModuleDependencyResolver;
import com.devmanchego.jtestforge.build.ResolvedClasspath;
import com.devmanchego.jtestforge.config.ProjectConfig;
import com.devmanchego.jtestforge.model.ModuleDependency;
import com.devmanchego.jtestforge.util.ExecutableResolver;
import com.devmanchego.jtestforge.util.ProcessRunner;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Where {@code scan} and {@code generate} get the target module's dependencies and Java
 * level from: a saved {@code mvn dependency:tree} when {@code project.dependencyTreeFile}
 * is set - no Maven call at all - otherwise Maven itself, under {@code project.javaHome}
 * when one is configured. Everything is resolved lazily and at most once.
 */
final class ModuleResolution {

    private static final Duration MAVEN_TIMEOUT = Duration.ofMinutes(2);
    private static final int MAX_UNRESOLVED_LISTED = 10;

    private final ProjectConfig project;
    private final Path modulePath;
    private final Path configBaseDir;
    private final Path treeFile;
    private final Path javaHome;
    private final Consumer<String> verboseSink;
    private final List<String> notes = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();
    private int reportedNotes;
    private int reportedWarnings;

    private MavenLocalRepository localRepository;
    private DependencyTreeResolver treeResolver;
    private DetectedJavaVersion javaVersion;

    ModuleResolution(ProjectConfig project, Path modulePath, Path configBaseDir) {
        this(project, modulePath, configBaseDir, null);
    }

    /** @param verboseSink receives every Maven command this resolves dependencies/classpath with - see {@code -v} */
    ModuleResolution(ProjectConfig project, Path modulePath, Path configBaseDir, Consumer<String> verboseSink) {
        this.project = project;
        this.modulePath = modulePath;
        this.configBaseDir = configBaseDir;
        this.treeFile = isSet(project.dependencyTreeFile()) ? resolve(project.dependencyTreeFile()) : null;
        this.javaHome = isSet(project.javaHome()) ? Path.of(project.javaHome()) : null;
        this.verboseSink = verboseSink;
    }

    List<Path> compileClasspath() {
        if (treeFile == null) {
            return new MavenClasspathResolver(processRunner(), project.mavenExecutable(), project.mavenArgs(),
                    javaHome).resolveCompileClasspath(modulePath, MAVEN_TIMEOUT);
        }
        ResolvedClasspath classpath = treeResolver().compileClasspath();
        reportUnresolved(classpath.unresolved());
        return classpath.entries();
    }

    List<ModuleDependency> dependencies() {
        if (treeFile == null) {
            return new ModuleDependencyResolver(processRunner(), project.mavenExecutable(), project.mavenArgs(),
                    javaHome).resolveDependencies(modulePath, MAVEN_TIMEOUT);
        }
        return treeResolver().dependencies();
    }

    private ProcessRunner processRunner() {
        return new ProcessRunner(java.nio.charset.Charset.defaultCharset(), verboseSink);
    }

    /** @throws IllegalArgumentException if {@code project.javaVersion} is not a Java version */
    DetectedJavaVersion javaVersion() {
        if (javaVersion == null) {
            javaVersion = new JavaVersionDetector(localRepository()).detect(modulePath, project.javaVersion());
            describeJavaLevel();
        }
        return javaVersion;
    }

    /** Prints whatever notes and warnings were established since the previous call. */
    void report(ConsoleOutput console) {
        notes.subList(reportedNotes, notes.size()).forEach(console::info);
        warnings.subList(reportedWarnings, warnings.size()).forEach(console::warn);
        reportedNotes = notes.size();
        reportedWarnings = warnings.size();
    }

    private DependencyTreeResolver treeResolver() {
        if (treeResolver == null) {
            treeResolver = DependencyTreeResolver.forModule(treeFile, modulePath, localRepository());
            notes.add("Dependencies of " + treeResolver.moduleKey() + " read from " + treeFile + " (Maven not run)");
            notes.add("Local Maven repository: " + localRepository.path() + " (" + localRepository.source() + ")");
        }
        return treeResolver;
    }

    private MavenLocalRepository localRepository() {
        if (localRepository == null) {
            localRepository = MavenLocalRepository.detect(
                    isSet(project.localRepository()) ? resolve(project.localRepository()).toString() : null,
                    project.mavenArgs(),
                    Path.of(System.getProperty("user.home")),
                    System.getenv(),
                    () -> ExecutableResolver.resolve(project.mavenExecutable(), ExecutableResolver.systemPathDirectories()));
        }
        return localRepository;
    }

    private void describeJavaLevel() {
        if (javaVersion.isKnown()) {
            notes.add("Java level of the module: " + javaVersion.release() + " (" + javaVersion.source() + ")");
        } else {
            warnings.add("Could not detect the module's Java level (no compiler settings in its pom chain and "
                    + "no built classes); parsing at Java 21. Set project.javaVersion or --java-version.");
        }
        if (javaHome == null) {
            return;
        }
        int jdkRelease = JavaVersionDetector.javaHomeRelease(javaHome);
        if (jdkRelease > 0 && javaVersion.isKnown() && jdkRelease < javaVersion.release()) {
            warnings.add("project.javaHome is a Java " + jdkRelease + " JDK (" + javaHome + "), older than the "
                    + "module's Java " + javaVersion.release() + " - Maven will not be able to compile it.");
        } else if (jdkRelease > 0) {
            notes.add("Maven runs on the JDK at " + javaHome + " (Java " + jdkRelease + ")");
        }
    }

    private void reportUnresolved(List<String> unresolved) {
        if (unresolved.isEmpty()) {
            return;
        }
        StringBuilder message = new StringBuilder(unresolved.size()
                + " dependency(ies) from the tree are not on disk and are skipped; types from them resolve by name only:");
        unresolved.stream().limit(MAX_UNRESOLVED_LISTED).forEach(line -> message.append("\n  ").append(line));
        if (unresolved.size() > MAX_UNRESOLVED_LISTED) {
            message.append("\n  ... and ").append(unresolved.size() - MAX_UNRESOLVED_LISTED).append(" more");
        }
        warnings.add(message.toString());
    }

    /** Config-file paths are relative to the config file, like prompt templates. */
    private Path resolve(String rawPath) {
        Path path = Path.of(rawPath);
        return path.isAbsolute() || configBaseDir == null ? path : configBaseDir.resolve(path).normalize();
    }

    private static boolean isSet(String value) {
        return value != null && !value.isBlank();
    }
}
