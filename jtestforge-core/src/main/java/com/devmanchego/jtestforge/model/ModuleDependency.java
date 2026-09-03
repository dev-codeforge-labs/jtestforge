package com.devmanchego.jtestforge.model;

import java.util.Objects;

/**
 * One entry of the target module's resolved dependency list, as reported by
 * {@code mvn dependency:list} — jtestforge-specification.md §7.3.
 *
 * @param groupId    Maven group id
 * @param artifactId Maven artifact id
 * @param version    resolved version
 * @param scope      Maven scope ({@code compile}, {@code test}, ...), lowercase
 */
public record ModuleDependency(String groupId, String artifactId, String version, String scope) {

    public ModuleDependency {
        Objects.requireNonNull(groupId, "groupId");
        Objects.requireNonNull(artifactId, "artifactId");
        version = version == null ? "" : version;
        scope = scope == null ? "compile" : scope;
    }

    public boolean is(String groupId, String artifactId) {
        return this.groupId.equals(groupId) && this.artifactId.equals(artifactId);
    }

    public boolean hasArtifactId(String artifactId) {
        return this.artifactId.equals(artifactId);
    }
}
