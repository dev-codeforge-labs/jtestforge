package com.devmanchego.jtestforge.build;

import java.nio.file.Path;
import java.util.List;

/**
 * @param entries    classpath entries that exist on disk
 * @param unresolved one human-readable line per dependency that could not be located
 */
public record ResolvedClasspath(List<Path> entries, List<String> unresolved) {

    public ResolvedClasspath {
        entries = List.copyOf(entries);
        unresolved = List.copyOf(unresolved);
    }
}
