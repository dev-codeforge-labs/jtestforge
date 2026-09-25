package com.devmanchego.jtestforge.build;

/** A dependency tree file that cannot be read, or that does not describe the target module. */
public final class DependencyTreeException extends RuntimeException {

    public DependencyTreeException(String message) {
        super(message);
    }

    public DependencyTreeException(String message, Throwable cause) {
        super(message, cause);
    }
}
