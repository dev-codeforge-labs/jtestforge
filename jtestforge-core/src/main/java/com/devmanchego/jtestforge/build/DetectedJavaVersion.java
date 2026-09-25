package com.devmanchego.jtestforge.build;

/**
 * The Java release the target module's code is written for.
 *
 * @param release feature release (8, 11, 17...), or 0 when it could not be determined
 * @param source  where the value came from, for the run's diagnostic output
 */
public record DetectedJavaVersion(int release, String source) {

    public static DetectedJavaVersion unknown() {
        return new DetectedJavaVersion(0, "not detected");
    }

    public boolean isKnown() {
        return release > 0;
    }
}
