package com.devmanchego.jtestforge.build;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * The handful of {@code pom.xml} facts JTestForge reads without running Maven: identity,
 * parent, properties, reactor modules and {@code maven-compiler-plugin} settings.
 * {@code groupId}/{@code version} already fall back to the parent's when not declared.
 *
 * @param compilerConfiguration {@code release}/{@code source}/{@code target} from the
 *                              compiler plugin's configuration, raw (may hold {@code ${...}})
 */
public record PomInfo(
        Path pomFile,
        String groupId,
        String artifactId,
        String version,
        String packaging,
        ParentReference parent,
        Map<String, String> properties,
        List<String> modules,
        Map<String, String> compilerConfiguration) {

    public PomInfo {
        properties = Map.copyOf(properties);
        modules = List.copyOf(modules);
        compilerConfiguration = Map.copyOf(compilerConfiguration);
    }

    /** @param relativePath {@code ""} when the pom declares an empty {@code <relativePath/>} */
    public record ParentReference(String groupId, String artifactId, String version, String relativePath) {
    }

    public String key() {
        return groupId + ":" + artifactId;
    }

    public Path directory() {
        return pomFile.getParent();
    }
}
