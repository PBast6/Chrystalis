package com.chrystalis.mockserver.web;

import com.chrystalis.mockserver.config.EndpointConfig;
import com.chrystalis.mockserver.config.EndpointConfigLoader;
import com.chrystalis.mockserver.config.EndpointRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Prueft den Dispatch und den Aufbau der Antwort ohne echten Serverstart. Der Port wird dem
 * Mock-Request gesetzt, weil der Controller anhand von {@code getLocalPort()} verteilt.
 */
class DynamicEndpointControllerTest {

    private static final int PORT = 8080;
    private static final int OTHER_PORT = 9090;
    private static final int UNCONFIGURED_PORT = 7070;

    private static MockMvc mockMvc;

    @BeforeAll
    static void setUp() {
        String configuration = """
                {"endpoints": [
                  {"name": "leer",        "port": 8080, "method": "DELETE", "path": "/api/items/1",
                   "response": {"status": 204}},
                  {"name": "liste",       "port": 8080, "method": "GET",    "path": "/api/items",
                   "response": {"body": [{"id": 1}, {"id": 2}]}},
                  {"name": "zahl",        "port": 8080, "method": "GET",    "path": "/api/items/count",
                   "response": {"body": 2}},
                  {"name": "jsonText",    "port": 8080, "method": "GET",    "path": "/api/items/name",
                   "response": {"body": "Ada"}},
                  {"name": "klartext",    "port": 8080, "method": "GET",    "path": "/health",
                   "response": {"headers": {"content-type": "text/plain; charset=UTF-8"}, "body": "OK"}},
                  {"name": "header",      "port": 8080, "method": "POST",   "path": "/api/tickets",
                   "response": {"status": 201,
                                "headers": {"Location": "/api/tickets/7", "X-Request-Id": "demo-1"},
                                "body": {"id": 7}}},
                  {"name": "aendern",     "port": 8080, "method": "PUT",    "path": "/api/items/1",
                   "expectedPayload": {"id": 1, "title": "neu"},
                   "response": {"body": {"updated": true}}},
                  {"name": "teilaendern", "port": 8080, "method": "PATCH",  "path": "/api/items/1",
                   "response": {"body": {"patched": true}}},
                  {"name": "kopf",        "port": 8080, "method": "HEAD",   "path": "/api/items",
                   "response": {"status": 200}},
                  {"name": "anderer Port","port": 9090, "method": "GET",    "path": "/ping",
                   "response": {"headers": {"Content-Type": "text/plain"}, "body": "pong"}}
                ]}""";

        EndpointConfig config = new EndpointConfigLoader()
                .parse(configuration.getBytes(StandardCharsets.UTF_8), "test");
        DynamicEndpointController controller =
                new DynamicEndpointController(EndpointRegistry.of(config), new ObjectMapper());
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    /** Setzt den Port, auf dem der Request angeblich ankam. */
    private static RequestPostProcessor onPort(int port) {
        return request -> {
            request.setLocalPort(port);
            return request;
        };
    }

    private static MockHttpServletRequestBuilder get(int port, String path) {
        return MockMvcRequestBuilders.get(path).with(onPort(port));
    }

    @Test
    @DisplayName("204 liefert einen leeren Body")
    void emptyBodyForNoContent() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.delete("/api/items/1").with(onPort(PORT)))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
    }

    @Test
    @DisplayName("Ein Array als Body wird als JSON-Array ausgeliefert")
    void arrayBody() throws Exception {
        mockMvc.perform(get(PORT, "/api/items"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(2)))
                .andExpect(jsonPath("$[1].id").value(2));
    }

    @Test
    @DisplayName("Eine nackte Zahl und ein Text bleiben gueltiges JSON")
    void scalarBodies() throws Exception {
        mockMvc.perform(get(PORT, "/api/items/count"))
                .andExpect(status().isOk())
                .andExpect(content().string("2"));

        mockMvc.perform(get(PORT, "/api/items/name"))
                .andExpect(status().isOk())
                .andExpect(content().string("\"Ada\""));
    }

    @Test
    @DisplayName("Bei text/plain wird der Text ohne Anfuehrungszeichen ausgeliefert")
    void plainTextBody() throws Exception {
        mockMvc.perform(get(PORT, "/health"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/plain"))
                .andExpect(content().string("OK"));
    }

    @Test
    @DisplayName("Ein klein geschriebener content-type wird nicht durch den Default ersetzt")
    void lowercaseContentTypeIsKept() throws Exception {
        mockMvc.perform(get(PORT, "/health"))
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE,
                        org.hamcrest.Matchers.containsString("text/plain")));
    }

    @Test
    @DisplayName("Konfigurierte Zusatzheader landen unveraendert in der Antwort")
    void customHeadersArePassedThrough() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.post("/api/tickets").with(onPort(PORT)))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/tickets/7"))
                .andExpect(header().string("X-Request-Id", "demo-1"))
                .andExpect(jsonPath("$.id").value(7));
    }

    @Test
    @DisplayName("PUT prueft den Payload wie POST")
    void putChecksPayload() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.put("/api/items/1")
                        .with(onPort(PORT))
                        .contentType("application/json")
                        .content("{\"id\": 1, \"title\": \"neu\", \"zusatz\": true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updated").value(true));

        mockMvc.perform(MockMvcRequestBuilders.put("/api/items/1")
                        .with(onPort(PORT))
                        .contentType("application/json")
                        .content("{\"id\": 1, \"title\": \"alt\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("PAYLOAD_MISMATCH"))
                .andExpect(jsonPath("$.mismatches[0].path").value("$.title"));
    }

    @Test
    @DisplayName("PATCH und HEAD funktionieren wie konfiguriert")
    void patchAndHeadAreSupported() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.patch("/api/items/1").with(onPort(PORT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.patched").value(true));

        mockMvc.perform(MockMvcRequestBuilders.head("/api/items").with(onPort(PORT)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Ein Port ohne jede Konfiguration liefert 404")
    void unconfiguredPortReturnsNotFound() throws Exception {
        mockMvc.perform(get(UNCONFIGURED_PORT, "/api/items"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NO_MATCHING_ENDPOINT"))
                .andExpect(jsonPath("$.port").value(UNCONFIGURED_PORT));
    }

    @Test
    @DisplayName("Derselbe Pfad auf dem anderen Port liefert dessen Antwort")
    void otherPortServesItsOwnEndpoint() throws Exception {
        mockMvc.perform(get(OTHER_PORT, "/ping"))
                .andExpect(status().isOk())
                .andExpect(content().string("pong"));

        mockMvc.perform(get(PORT, "/ping"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Die falsche Methode liefert 405 mit Allow-Header")
    void methodNotAllowed() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.delete("/api/items").with(onPort(PORT)))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string(HttpHeaders.ALLOW, org.hamcrest.Matchers.containsString("GET")))
                .andExpect(jsonPath("$.error").value("METHOD_NOT_ALLOWED"));
    }
}
