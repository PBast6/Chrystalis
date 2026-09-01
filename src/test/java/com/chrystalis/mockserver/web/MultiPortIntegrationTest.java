package com.chrystalis.mockserver.web;

import com.chrystalis.mockserver.MockServerBootstrap;
import com.chrystalis.mockserver.config.EndpointConfigLoader;
import com.chrystalis.mockserver.config.EndpointRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.util.TestSocketUtils;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Startet den Server so, wie {@code main()} es tut: Konfiguration laden, Registry bauen, Tomcat
 * mit einem Connector pro Port hochfahren. Geprueft wird dann ueber echte HTTP-Requests.
 */
class MultiPortIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .proxy(HttpClient.Builder.NO_PROXY)
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private static int primaryPort;
    private static int secondaryPort;
    private static ConfigurableApplicationContext context;

    @BeforeAll
    static void startServer(@TempDir Path dir) throws IOException {
        primaryPort = TestSocketUtils.findAvailableTcpPort();
        do {
            secondaryPort = TestSocketUtils.findAvailableTcpPort();
        } while (secondaryPort == primaryPort);

        Path config = dir.resolve("endpoints.json");
        Files.writeString(config, """
                {
                  "endpoints": [
                    {
                      "name": "createUser",
                      "port": %d,
                      "method": "POST",
                      "path": "/api/users",
                      "expectedPayload": {"user": {"name": "Ada", "role": "admin"}},
                      "response": {"status": 201, "body": {"id": 42, "status": "CREATED"}}
                    },
                    {
                      "name": "health",
                      "port": %d,
                      "method": "GET",
                      "path": "/api/health",
                      "response": {"body": {"status": "UP"}}
                    },
                    {
                      "name": "legacyPing",
                      "port": %d,
                      "method": "GET",
                      "path": "/ping",
                      "response": {"headers": {"Content-Type": "text/plain; charset=UTF-8"}, "body": "pong"}
                    }
                  ]
                }
                """.formatted(primaryPort, primaryPort, secondaryPort));

        EndpointRegistry registry = EndpointRegistry.of(new EndpointConfigLoader().loadFrom(config));
        assertThat(registry.primaryPort()).isEqualTo(Math.min(primaryPort, secondaryPort));
        context = MockServerBootstrap.run(registry);
    }

    @AfterAll
    static void stopServer() {
        if (context != null) {
            context.close();
        }
    }

    private static HttpResponse<String> send(HttpRequest request) throws Exception {
        return CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> get(int port, String path) throws Exception {
        return send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build());
    }

    private static HttpResponse<String> post(int port, String path, String body) throws Exception {
        return send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build());
    }

    private static JsonNode body(HttpResponse<String> response) throws IOException {
        return MAPPER.readTree(response.body());
    }

    @Test
    @DisplayName("Passender Payload liefert die konfigurierte Antwort")
    void returnsConfiguredResponseForMatchingPayload() throws Exception {
        HttpResponse<String> response = post(primaryPort, "/api/users", """
                {"user": {"name": "Ada", "role": "admin", "extra": 1}}""");

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(response.headers().firstValue("Content-Type")).hasValue("application/json");
        assertThat(body(response).get("id").asInt()).isEqualTo(42);
        assertThat(body(response).get("status").asText()).isEqualTo("CREATED");
    }

    @Test
    @DisplayName("Abweichender Payload liefert 400 mit den Abweichungen")
    void returnsBadRequestForMismatchingPayload() throws Exception {
        HttpResponse<String> response = post(primaryPort, "/api/users", """
                {"user": {"name": "Ada", "role": "guest"}}""");

        assertThat(response.statusCode()).isEqualTo(400);
        JsonNode error = body(response);
        assertThat(error.get("error").asText()).isEqualTo("PAYLOAD_MISMATCH");
        assertThat(error.get("endpoint").asText()).isEqualTo("createUser");
        assertThat(error.get("mismatches")).hasSize(1);
        assertThat(error.get("mismatches").get(0).get("path").asText()).isEqualTo("$.user.role");
        assertThat(error.get("mismatches").get(0).get("actual").asText()).isEqualTo("guest");
    }

    @Test
    @DisplayName("Ein Body, der kein JSON ist, liefert 400")
    void returnsBadRequestForNonJsonBody() throws Exception {
        HttpResponse<String> response = post(primaryPort, "/api/users", "kein json");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(body(response).get("error").asText()).isEqualTo("INVALID_JSON_BODY");
    }

    @Test
    @DisplayName("Endpunkt ohne erwarteten Payload antwortet direkt")
    void returnsResponseWithoutPayloadCheck() throws Exception {
        HttpResponse<String> response = get(primaryPort, "/api/health");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(body(response).get("status").asText()).isEqualTo("UP");
    }

    @Test
    @DisplayName("Der zweite Port bedient seine eigenen Endpunkte, auch mit text/plain")
    void secondPortServesItsOwnEndpoints() throws Exception {
        HttpResponse<String> response = get(secondaryPort, "/ping");

        assertThat(response.statusCode()).isEqualTo(200);
        // Spring normalisiert den Header, deshalb wird der Medientyp und nicht der Rohtext verglichen.
        MediaType contentType = MediaType.parseMediaType(
                response.headers().firstValue("Content-Type").orElseThrow());
        assertThat(contentType.isCompatibleWith(MediaType.TEXT_PLAIN)).isTrue();
        assertThat(contentType.getCharset()).isEqualTo(StandardCharsets.UTF_8);
        assertThat(response.body()).isEqualTo("pong");
    }

    @Test
    @DisplayName("Endpunkte sind zwischen den Ports getrennt")
    void endpointsAreNotSharedBetweenPorts() throws Exception {
        assertThat(get(primaryPort, "/ping").statusCode()).isEqualTo(404);
        assertThat(get(secondaryPort, "/api/health").statusCode()).isEqualTo(404);

        JsonNode error = body(get(primaryPort, "/ping"));
        assertThat(error.get("error").asText()).isEqualTo("NO_MATCHING_ENDPOINT");
        assertThat(error.get("port").asInt()).isEqualTo(primaryPort);
    }

    @Test
    @DisplayName("Falsche Methode liefert 405 mit Allow-Header")
    void returnsMethodNotAllowed() throws Exception {
        HttpResponse<String> response = send(HttpRequest
                .newBuilder(URI.create("http://localhost:" + primaryPort + "/api/health"))
                .DELETE()
                .build());

        assertThat(response.statusCode()).isEqualTo(405);
        assertThat(response.headers().firstValue("Allow")).hasValue("GET");
        assertThat(body(response).get("allowedMethods")).hasSize(1);
    }

    @Test
    @DisplayName("Ein unbekannter Pfad liefert 404 statt einer Spring-Fehlerseite")
    void returnsNotFoundForUnknownPath() throws Exception {
        HttpResponse<String> response = get(primaryPort, "/gibt/es/nicht");

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(body(response).get("path").asText()).isEqualTo("/gibt/es/nicht");
    }
}
