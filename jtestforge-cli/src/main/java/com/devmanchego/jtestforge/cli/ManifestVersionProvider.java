package com.devmanchego.jtestforge.cli;

import picocli.CommandLine.IVersionProvider;

/**
 * Resolves the version printed by {@code --version} from the running jar's manifest
 * (populated by maven-shade-plugin's {@code ManifestResourceTransformer} at build time),
 * falling back to a development marker when run from an IDE or exploded classpath where
 * no manifest is present.
 */
public final class ManifestVersionProvider implements IVersionProvider {

    @Override
    public String[] getVersion() {
        String implementationVersion = getClass().getPackage().getImplementationVersion();
        String version = implementationVersion != null ? implementationVersion : "development";
        return new String[] {"JTestForge " + version};
    }
}
