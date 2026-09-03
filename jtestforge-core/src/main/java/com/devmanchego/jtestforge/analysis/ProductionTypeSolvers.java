package com.devmanchego.jtestforge.analysis;

import com.github.javaparser.resolution.TypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ClassLoaderTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JarTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Builds the {@link TypeSolver} a production-class scan resolves collaborator and
 * parameter types against — jtestforge-specification.md §7.1.
 *
 * <p>Three layers, combined: the JDK itself (reflection over the running JVM), the
 * module's own source (so a collaborator that is another class in the same module
 * resolves without needing it compiled first), and the module's resolved compile
 * classpath (every external dependency, typically from
 * {@code MavenClasspathResolver}). A missing or unreadable classpath entry is a real
 * setup problem and is reported loudly rather than silently skipped, per §2's "fail
 * loudly on a missing prerequisite" principle.
 */
public final class ProductionTypeSolvers {

    private ProductionTypeSolvers() {
    }

    public static TypeSolver forModule(Path mainSourceRoot, List<Path> compileClasspath) {
        CombinedTypeSolver combined = new CombinedTypeSolver();
        // false = resolve any class visible via reflection, not only java.*/javax.*.
        // The no-arg constructor defaults to JRE-only, which would silently fail to
        // resolve a collaborator like org.slf4j.Logger even when it is genuinely on the
        // running JVM's classpath - exactly the case this module's own reflective
        // classpath (and any JarTypeSolver/ClassLoaderTypeSolver added below) exists to
        // cover.
        combined.add(new ReflectionTypeSolver(false));
        combined.add(new JavaParserTypeSolver(mainSourceRoot));
        for (Path entry : compileClasspath) {
            combined.add(solverFor(entry));
        }
        return combined;
    }

    private static TypeSolver solverFor(Path entry) {
        if (Files.isDirectory(entry)) {
            return new ClassLoaderTypeSolver(new URLClassLoader(new URL[] {toUrl(entry)}));
        }
        try {
            return new JarTypeSolver(entry);
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Failed to read classpath entry as a jar: " + entry, e);
        }
    }

    private static URL toUrl(Path path) {
        try {
            return path.toUri().toURL();
        } catch (MalformedURLException e) {
            throw new IllegalArgumentException("Invalid classpath entry: " + path, e);
        }
    }
}
