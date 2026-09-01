package com.chrystalis.mockserver.web;

import com.chrystalis.mockserver.MockServerBootstrap;
import com.chrystalis.mockserver.config.EndpointConfig;
import com.chrystalis.mockserver.config.EndpointConfigLoader;
import com.chrystalis.mockserver.config.EndpointDefinition;
import com.chrystalis.mockserver.config.EndpointRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.util.TestSocketUtils;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spielt die ausgelieferte Beispielkonfiguration {@code files/examples/06-rest-api.json} gegen
 * einen echten Server durch. Damit ist belegt, dass die Beispiele nicht nur parsen, sondern
 * auch das tun, was in {@code files/examples/README.md} steht.
 *
 * <p>Die fest verdrahteten Ports der Datei werden auf freie Ports umgeschrieben, damit der Test
 * auch dann laeuft, wenn 8080 oder 9090 belegt sind.
 */
class ExampleServerIntegrationTest {

    private static final Path EXAMPLE = Path.of("files/examples/06-rest-api.json");
    private static final int CONFIGURED_API_PORT = 8080;
    private static final int CONFIGURED_ADMIN_PORT = 9090;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .proxy(HttpClient.Builder.NO_PROXY)
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private static final Map<Integer, Integer> PORT_MAPPING = new HashMap<>();

    private static int apiPort;
    private static int adminPort;
    private static ConfigurableApplicationContext context;

    @BeforeAll
    static void startServer() {
        EndpointRegistry registry = loadWithFreePorts(EXAMPLE);
        apiPort = PORT_MAPPING.get(CONFIGURED_API_PORT);
        adminPort = PORT_MAPPING.get(CONFIGURED_ADMIN_PORT);

        assertThat(apiPort).isNotEqualTo(adminPort);
        context = MockServerBootstrap.run(registry);
    }

    @AfterAll
    static void stopServer() {
        if (context != null) {
            context.close();
        }
    }

    /** Laedt die Beispieldatei und ersetzt jeden konfigurierten Port durch einen freien. */
    private static EndpointRegistry loadWithFreePorts(Path file) {
        EndpointConfig config = new EndpointConfigLoader().loadFrom(file);
        List<EndpointDefinition> remapped = config.endpoints().stream()
                .map(endpoint -> new EndpointDefinition(
                        endpoint.name(),
                        PORT_MAPPING.computeIfAbsent(endpoint.port(), original -> freePort()),
                        endpoint.method(),
                        endpoint.path(),
                        endpoint.expectedPayload(),
                        endpoint.response()))
                .toList();
        return new EndpointRegistry(remapped);
    }

    private static int freePort() {
        int port;
        do {
            port = TestSocketUtils.findAvailableTcpPort();
        } while (PORT_MAPPING.containsValue(port));
        return port;
    }

    private static HttpResponse<String> send(HttpRequest request) throws Exception {
        return CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static HttpRequest.Builder request(int port, String path) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json");
    }

    private static HttpResponse<String> get(int port, String path) throws Exception {
        return send(request(port, path).GET().build());
    }

    private static JsonNode body(HttpResponse<String> response) throws IOException {
        return MAPPER.readTree(response.body());
    }

    @Test
    @DisplayName("Token holen: passender Payload liefert 200")
    void authToken() throws Exception {
        HttpResponse<String> response = send(request(apiPort, "/api/v1/auth/token")
                .POST(HttpRequest.BodyPublishers.ofString("""
                        {"clientId": "demo", "clientSecret": "geheim"}"""))
                .build());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(body(response).get("accessToken").asText()).isEqualTo("demo-token");
    }

    @Test
    @DisplayName("Token holen: falsches Secret liefert 400 mit $.clientSecret")
    void authTokenWithWrongSecret() throws Exception {
        HttpResponse<String> response = send(request(apiPort, "/api/v1/auth/token")
                .POST(HttpRequest.BodyPublishers.ofString("""
                        {"clientId": "demo", "clientSecret": "falsch"}"""))
                .build());

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(body(response).get("mismatches").get(0).get("path").asText())
                .isEqualTo("$.clientSecret");
    }

    @Test
    @DisplayName("Liste liefert ein Array")
    void listBooks() throws Exception {
        HttpResponse<String> response = get(apiPort, "/api/v1/books");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(body(response).isArray()).isTrue();
        assertThat(body(response)).hasSize(2);
    }

    @Test
    @DisplayName("Der {id}-Platzhalter bedient jede Id, der exakte Pfad gewinnt aber")
    void readBookUsesPatternButExactPathWins() throws Exception {
        HttpResponse<String> viaPattern = get(apiPort, "/api/v1/books/1");
        assertThat(viaPattern.statusCode()).isEqualTo(200);
        assertThat(body(viaPattern).get("author").asText()).isEqualTo("Kafka");

        HttpResponse<String> exact = get(apiPort, "/api/v1/books/999");
        assertThat(exact.statusCode()).isEqualTo(404);
        assertThat(body(exact).get("error").asText()).isEqualTo("BOOK_NOT_FOUND");
    }

    @Test
    @DisplayName("Anlegen liefert 201 mit Location-Header")
    void createBook() throws Exception {
        HttpResponse<String> response = send(request(apiPort, "/api/v1/books")
                .POST(HttpRequest.BodyPublishers.ofString("""
                        {"title": "Das Schloss", "author": "Kafka", "year": 1926}"""))
                .build());

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(response.headers().firstValue("Location")).hasValue("/api/v1/books/3");
        assertThat(body(response).get("id").asInt()).isEqualTo(3);
    }

    @Test
    @DisplayName("Aendern prueft nur die konfigurierten Felder")
    void updateBook() throws Exception {
        HttpResponse<String> response = send(request(apiPort, "/api/v1/books/1")
                .PUT(HttpRequest.BodyPublishers.ofString("""
                        {"id": 1, "title": "Der Process", "author": "Kafka"}"""))
                .build());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(body(response).get("title").asText()).isEqualTo("Der Process");
    }

    @Test
    @DisplayName("Loeschen liefert 204 ohne Body")
    void deleteBook() throws Exception {
        HttpResponse<String> response = send(request(apiPort, "/api/v1/books/2")
                .DELETE()
                .build());

        assertThat(response.statusCode()).isEqualTo(204);
        assertThat(response.body()).isEmpty();
    }

    @Test
    @DisplayName("Der Betriebs-Port bedient Health und Info, der API-Port nicht")
    void adminPortIsSeparate() throws Exception {
        HttpResponse<String> health = get(adminPort, "/actuator/health");
        assertThat(health.statusCode()).isEqualTo(200);
        assertThat(body(health).get("status").asText()).isEqualTo("UP");

        HttpResponse<String> info = get(adminPort, "/actuator/info");
        assertThat(info.statusCode()).isEqualTo(200);
        assertThat(info.body()).isEqualTo("chrystalis-mockserver 1.0.0");

        assertThat(get(apiPort, "/actuator/health").statusCode()).isEqualTo(404);
        assertThat(get(adminPort, "/api/v1/books").statusCode()).isEqualTo(404);
    }
}
