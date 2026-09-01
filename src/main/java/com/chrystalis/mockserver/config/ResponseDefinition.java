package com.chrystalis.mockserver.config;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Die Antwort, die ein Endpunkt zurueckgibt, so wie sie in der JSON-Konfiguration steht.
 *
 * @param status  HTTP-Statuscode, Standard {@value #DEFAULT_STATUS}
 * @param headers zusaetzliche Response-Header, Standard {@code Content-Type: application/json}
 * @param body    beliebiges JSON (Objekt, Array oder Skalar); {@code null} bedeutet leerer Body
 */
public record ResponseDefinition(Integer status, Map<String, String> headers, JsonNode body) {

    public static final int DEFAULT_STATUS = 200;
    public static final String DEFAULT_CONTENT_TYPE = "application/json";

    public int statusOrDefault() {
        return status == null ? DEFAULT_STATUS : status;
    }

    /**
     * Header der Antwort inklusive eines {@code Content-Type}, falls die Konfiguration keinen setzt.
     */
    public Map<String, String> headersOrDefault() {
        Map<String, String> result = new LinkedHashMap<>();
        if (headers != null) {
            result.putAll(headers);
        }
        boolean hasContentType = result.keySet().stream().anyMatch("Content-Type"::equalsIgnoreCase);
        if (!hasContentType) {
            result.put("Content-Type", DEFAULT_CONTENT_TYPE);
        }
        return result;
    }
}
