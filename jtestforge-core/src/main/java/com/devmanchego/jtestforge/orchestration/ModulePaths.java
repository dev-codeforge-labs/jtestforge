package com.devmanchego.jtestforge.orchestration;

import java.nio.file.Path;

/** Resolves the module-relative file paths a {@code WorkUnit} records - one rule for backup and revert. */
final class ModulePaths {

    private ModulePaths() {
    }

    /** {@code file} against {@code modulePath} unless already absolute; absolute and normalised. */
    static Path resolve(Path modulePath, String file) {
        Path path = Path.of(file);
        return (path.isAbsolute() ? path : modulePath.resolve(path)).toAbsolutePath().normalize();
    }
}
