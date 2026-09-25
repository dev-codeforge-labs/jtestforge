package com.devmanchego.jtestforge.build;

import com.devmanchego.jtestforge.model.ModuleDependency;

/**
 * One resolved dependency line of {@code mvn dependency:tree} output.
 *
 * @param classifier empty when the coordinate has none
 * @param scope      lowercase Maven scope
 */
public record DependencyTreeEntry(
        String groupId, String artifactId, String type, String classifier, String version, String scope) {

    public String coordinate() {
        return groupId + ":" + artifactId + ":" + type + (classifier.isEmpty() ? "" : ":" + classifier)
                + ":" + version + ":" + scope;
    }

    public ModuleDependency toModuleDependency() {
        return new ModuleDependency(groupId, artifactId, version, scope);
    }
}
