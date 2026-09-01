package com.chrystalis.mockserver.web;

import com.chrystalis.mockserver.config.EndpointDefinition;
import com.chrystalis.mockserver.config.EndpointRegistry;
import com.chrystalis.mockserver.match.MatchResult;
import com.chrystalis.mockserver.match.PayloadMatcher;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StreamUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Nimmt jeden Request entgegen und beantwortet ihn anhand der Konfiguration.
 *
 * <ul>
 *   <li>kein Endpunkt fuer Port und Pfad: 404</li>
 *   <li>Pfad passt, Methode nicht: 405 mit {@code Allow}-Header</li>
 *   <li>erwarteter Payload gesetzt und Body passt nicht: 400 mit den Abweichungen</li>
 *   <li>sonst: der konfigurierte Status, die konfigurierten Header und der konfigurierte Body</li>
 * </ul>
 */
@RestController
public class DynamicEndpointController {

    private static final Logger log = LoggerFactory.getLogger(DynamicEndpointController.class);

    private final EndpointRegistry registry;
    private final ObjectMapper mapper;

    public DynamicEndpointController(EndpointRegistry registry, ObjectMapper mapper) {
        this.registry = registry;
        this.mapper = mapper;
    }

    @RequestMapping("/**")
    public ResponseEntity<?> handle(HttpServletRequest request) throws IOException {
        int port = request.getLocalPort();
        String method = request.getMethod();
        String path = request.getRequestURI();

        EndpointRegistry.Lookup lookup = registry.lookup(port, method, path);
        switch (lookup.status()) {
            case NOT_FOUND -> {
                log.info("{} {} auf Port {} -> 404 (kein passender Endpunkt)", method, path, port);
                return jsonError(404, ApiError.noMatchingEndpoint(port, method, path), null);
            }
            case METHOD_NOT_ALLOWED -> {
                List<String> allowed = new ArrayList<>(lookup.allowedMethods());
                log.info("{} {} auf Port {} -> 405 (erlaubt: {})", method, path, port, allowed);
                HttpHeaders headers = new HttpHeaders();
                headers.add(HttpHeaders.ALLOW, String.join(", ", allowed));
                return jsonError(405, ApiError.methodNotAllowed(port, method, path, allowed), headers);
            }
            default -> {
                // faellt unten durch zur Auswertung des Treffers
            }
        }

        EndpointDefinition endpoint = lookup.endpoint();
        if (endpoint.hasExpectedPayload()) {
            String rawBody = StreamUtils.copyToString(request.getInputStream(), StandardCharsets.UTF_8);
            JsonNode actual;
            try {
                actual = rawBody.isBlank() ? null : mapper.readTree(rawBody);
            } catch (JsonProcessingException e) {
                log.info("{} {} auf Port {} -> 400 (Body ist kein gueltiges JSON)", method, path, port);
                return jsonError(400, ApiError.invalidJsonBody(
                        port, method, path, endpoint.displayName(), e.getOriginalMessage()), null);
            }

            MatchResult result = PayloadMatcher.match(endpoint.expectedPayload(), actual);
            if (!result.matches()) {
                log.info("{} {} auf Port {} -> 400 ({} Abweichung(en) zu \"{}\")",
                        method, path, port, result.mismatches().size(), endpoint.displayName());
                return jsonError(400, ApiError.payloadMismatch(
                        port, method, path, endpoint.displayName(), result.mismatches()), null);
            }
        }

        log.info("{} {} auf Port {} -> {} (\"{}\")",
                method, path, port, endpoint.response().statusOrDefault(), endpoint.displayName());
        return configuredResponse(endpoint);
    }

    /**
     * Baut die in der Konfiguration hinterlegte Antwort. Der Body wird als Bytes geschrieben,
     * damit auch Nicht-JSON-Content-Types wie {@code text/plain} exakt so ausgeliefert werden,
     * wie sie konfiguriert sind.
     */
    private ResponseEntity<byte[]> configuredResponse(EndpointDefinition endpoint) throws JsonProcessingException {
        var definition = endpoint.response();
        HttpHeaders headers = new HttpHeaders();
        for (Map.Entry<String, String> header : definition.headersOrDefault().entrySet()) {
            headers.add(header.getKey(), header.getValue());
        }

        JsonNode body = definition.body();
        byte[] payload;
        if (body == null || body.isNull()) {
            payload = new byte[0];
        } else if (body.isTextual() && !isJson(headers.getFirst(HttpHeaders.CONTENT_TYPE))) {
            payload = body.textValue().getBytes(StandardCharsets.UTF_8);
        } else {
            payload = mapper.writeValueAsBytes(body);
        }

        return ResponseEntity.status(definition.statusOrDefault()).headers(headers).body(payload);
    }

    private ResponseEntity<ApiError> jsonError(int status, ApiError error, HttpHeaders headers) {
        HttpHeaders responseHeaders = headers == null ? new HttpHeaders() : headers;
        responseHeaders.setContentType(MediaType.APPLICATION_JSON);
        return ResponseEntity.status(status).headers(responseHeaders).body(error);
    }

    private static boolean isJson(String contentType) {
        return contentType != null && contentType.toLowerCase().contains("json");
    }
}
