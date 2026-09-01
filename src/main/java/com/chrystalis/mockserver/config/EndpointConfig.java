package com.chrystalis.mockserver.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * Wurzelobjekt der JSON-Konfiguration.
 *
 * <p>Nur {@code $schema} wird als Zusatzfeld geduldet: damit kann eine Konfigurationsdatei auf
 * {@code files/endpoints.schema.json} verweisen und in der IDE validiert werden. Jedes andere
 * unbekannte Feld bleibt ein Fehler, damit Tippfehler auffallen.
 */
@JsonIgnoreProperties("$schema")
public record EndpointConfig(List<EndpointDefinition> endpoints) {
}
