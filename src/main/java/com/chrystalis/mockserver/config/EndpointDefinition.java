package com.chrystalis.mockserver.config;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Ein Endpunkt aus der JSON-Konfiguration: Pfad und Methode auf einem Port, ein optional
 * erwarteter Payload und der Wert, der zurueckgegeben wird.
 *
 * @param name            optionaler Name fuer Logs und Fehlermeldungen
 * @param port            Port, auf dem dieser Endpunkt erreichbar ist
 * @param method          HTTP-Methode
 * @param path            Pfad, optional mit Ant-Muster ({@code /api/users/*})
 * @param expectedPayload erwarteter Request-Body; {@code null} bedeutet keine Pruefung
 * @param response        die zurueckzugebende Antwort
 */
public record EndpointDefinition(
        String name,
        Integer port,
        String method,
        String path,
        JsonNode expectedPayload,
        ResponseDefinition response) {

    /**
     * Name fuer Meldungen; faellt auf {@code METHOD path} zurueck, wenn kein Name gesetzt ist.
     */
    public String displayName() {
        if (name != null && !name.isBlank()) {
            return name;
        }
        return method + " " + path;
    }

    public boolean hasExpectedPayload() {
        return expectedPayload != null && !expectedPayload.isNull();
    }
}
