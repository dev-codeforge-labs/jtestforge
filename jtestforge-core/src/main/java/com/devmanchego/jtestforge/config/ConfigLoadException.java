package com.devmanchego.jtestforge.config;

/** Thrown when {@code jtestforge.yaml} cannot even be parsed and bound. */
public final class ConfigLoadException extends RuntimeException {

    public ConfigLoadException(String message) {
        super(message);
    }

    public ConfigLoadException(String message, Throwable cause) {
        super(message, cause);
    }
}
