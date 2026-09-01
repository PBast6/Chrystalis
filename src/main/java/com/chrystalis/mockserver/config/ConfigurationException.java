package com.chrystalis.mockserver.config;

/**
 * Wird geworfen, wenn die Endpunkt-Konfiguration fehlt, nicht lesbar oder ungueltig ist.
 * Der Server startet in diesem Fall bewusst gar nicht erst.
 */
public class ConfigurationException extends RuntimeException {

    public ConfigurationException(String message) {
        super(message);
    }

    public ConfigurationException(String message, Throwable cause) {
        super(message, cause);
    }
}
