package com.devmanchego.jtestforge.config;

/**
 * One issue found while loading or validating a config, tied to the YAML path that
 * caused it (e.g. {@code "harden.maxTierForMutation"}), so a user can find it without
 * having to search the whole file.
 */
public record ConfigViolation(String path, String message) {

    @Override
    public String toString() {
        return path + ": " + message;
    }
}
