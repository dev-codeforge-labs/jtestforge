package com.devmanchego.jtestforge.build;

/**
 * Thrown when {@code mvn dependency:build-classpath} fails or times out — a missing
 * prerequisite that jtestforge-specification.md §2 requires to fail loudly, not silently.
 */
public final class MavenClasspathResolutionException extends RuntimeException {

    public MavenClasspathResolutionException(String message) {
        super(message);
    }

    public MavenClasspathResolutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
