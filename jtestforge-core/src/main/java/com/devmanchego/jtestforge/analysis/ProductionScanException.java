package com.devmanchego.jtestforge.analysis;

import java.nio.file.Path;

/**
 * Thrown when a {@code .java} file under the main source root cannot be parsed.
 *
 * <p>jtestforge-specification.md §2 requires the module to already build green before a
 * run starts; a file that fails to parse means that precondition does not hold, so this
 * is reported loudly rather than the file being silently skipped.
 */
public final class ProductionScanException extends RuntimeException {

    public ProductionScanException(Path file, String message) {
        super("Failed to parse " + file + ": " + message);
    }
}
