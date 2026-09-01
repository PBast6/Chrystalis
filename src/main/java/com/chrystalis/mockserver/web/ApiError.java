package com.chrystalis.mockserver.web;

import com.chrystalis.mockserver.match.Mismatch;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Einheitlicher Fehler-Body des Mock-Servers.
 *
 * @param error          maschinenlesbarer Fehlercode
 * @param message        Beschreibung im Klartext
 * @param port           Port, auf dem der Request ankam
 * @param method         HTTP-Methode des Requests
 * @param path           Pfad des Requests
 * @param endpoint       Name des betroffenen Endpunkts, sofern einer gefunden wurde
 * @param allowedMethods bei 405 die auf dem Pfad definierten Methoden
 * @param mismatches     bei 400 die Abweichungen zum erwarteten Payload
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(
        String error,
        String message,
        Integer port,
        String method,
        String path,
        String endpoint,
        List<String> allowedMethods,
        List<Mismatch> mismatches) {

    public static ApiError noMatchingEndpoint(int port, String method, String path) {
        return new ApiError("NO_MATCHING_ENDPOINT",
                "Kein Endpunkt fuer " + method + " " + path + " auf Port " + port + " konfiguriert.",
                port, method, path, null, null, null);
    }

    public static ApiError methodNotAllowed(int port, String method, String path, List<String> allowed) {
        return new ApiError("METHOD_NOT_ALLOWED",
                "Auf " + path + " (Port " + port + ") ist " + method + " nicht konfiguriert. Erlaubt: "
                        + String.join(", ", allowed) + ".",
                port, method, path, null, allowed, null);
    }

    public static ApiError invalidJsonBody(int port, String method, String path, String endpoint, String detail) {
        return new ApiError("INVALID_JSON_BODY",
                "Der Request-Body ist kein gueltiges JSON: " + detail,
                port, method, path, endpoint, null, null);
    }

    public static ApiError payloadMismatch(int port, String method, String path, String endpoint,
                                           List<Mismatch> mismatches) {
        return new ApiError("PAYLOAD_MISMATCH",
                "Der Request-Body entspricht nicht dem erwarteten Payload von \"" + endpoint + "\".",
                port, method, path, endpoint, null, mismatches);
    }
}
