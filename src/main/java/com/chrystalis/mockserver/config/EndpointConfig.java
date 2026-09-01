package com.chrystalis.mockserver.config;

import java.util.List;

/**
 * Wurzelobjekt der JSON-Konfiguration.
 */
public record EndpointConfig(List<EndpointDefinition> endpoints) {
}
