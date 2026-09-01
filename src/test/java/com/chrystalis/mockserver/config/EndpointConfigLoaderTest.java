package com.chrystalis.mockserver.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EndpointConfigLoaderTest {

    private final EndpointConfigLoader loader = new EndpointConfigLoader();

    private EndpointConfig parse(String json) {
        return loader.parse(json.getBytes(StandardCharsets.UTF_8), "test");
    }

    @Test
    @DisplayName("Eine gueltige Konfiguration wird mit Defaults gelesen")
    void parsesValidConfigurationWithDefaults() {
        EndpointConfig config = parse("""
                {"endpoints": [
                  {"name": "health", "port": 8080, "method": "get", "path": "/api/health/",
                   "response": {"body": {"status": "UP"}}}
                ]}""");

        assertThat(config.endpoints()).hasSize(1);
        EndpointDefinition endpoint = config.endpoints().get(0);
        assertThat(endpoint.method()).isEqualTo("GET");
        assertThat(endpoint.path()).isEqualTo("/api/health");
        assertThat(endpoint.hasExpectedPayload()).isFalse();
        assertThat(endpoint.response().statusOrDefault()).isEqualTo(200);
        assertThat(endpoint.response().headersOrDefault()).containsEntry("Content-Type", "application/json");
    }

    @Test
    @DisplayName("Ein konfigurierter Content-Type wird nicht ueberschrieben")
    void keepsConfiguredContentType() {
        EndpointConfig config = parse("""
                {"endpoints": [
                  {"port": 9090, "method": "GET", "path": "/ping",
                   "response": {"headers": {"Content-Type": "text/plain"}, "body": "pong"}}
                ]}""");

        assertThat(config.endpoints().get(0).response().headersOrDefault())
                .containsExactly(java.util.Map.entry("Content-Type", "text/plain"));
    }

    @Test
    @DisplayName("Doppelte Endpunkte auf demselben Port werden abgelehnt")
    void rejectsDuplicateEndpoints() {
        assertThatThrownBy(() -> parse("""
                {"endpoints": [
                  {"name": "a", "port": 8080, "method": "GET", "path": "/x", "response": {}},
                  {"name": "b", "port": 8080, "method": "GET", "path": "/x/", "response": {}}
                ]}"""))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("doppelte Definition")
                .hasMessageContaining("\"a\"");
    }

    @Test
    @DisplayName("Derselbe Pfad auf unterschiedlichen Ports ist kein Duplikat")
    void samePathOnDifferentPortsIsAllowed() {
        EndpointConfig config = parse("""
                {"endpoints": [
                  {"port": 8080, "method": "GET", "path": "/x", "response": {}},
                  {"port": 9090, "method": "GET", "path": "/x", "response": {}}
                ]}""");

        assertThat(config.endpoints()).hasSize(2);
    }

    @Test
    @DisplayName("Alle Feldfehler werden gesammelt gemeldet")
    void reportsAllFieldErrors() {
        assertThatThrownBy(() -> parse("""
                {"endpoints": [
                  {"name": "kaputt", "port": 70000, "method": "FETCH", "path": "api/x"}
                ]}"""))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("Port 70000")
                .hasMessageContaining("unbekannte HTTP-Methode")
                .hasMessageContaining("muss mit \"/\" beginnen")
                .hasMessageContaining("Feld \"response\" fehlt");
    }

    @Test
    @DisplayName("Eine leere Endpunktliste wird abgelehnt")
    void rejectsEmptyEndpointList() {
        assertThatThrownBy(() -> parse("{\"endpoints\": []}"))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("keine Endpunkte");
    }

    @Test
    @DisplayName("Ungueltiges JSON wird mit Hinweis abgelehnt")
    void rejectsInvalidJson() {
        assertThatThrownBy(() -> parse("{\"endpoints\": ["))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("kein gueltiges JSON");
    }

    @Test
    @DisplayName("Unbekannte Felder deuten auf Tippfehler hin und werden abgelehnt")
    void rejectsUnknownFields() {
        assertThatThrownBy(() -> parse("""
                {"endpoints": [
                  {"port": 8080, "method": "GET", "path": "/x", "respones": {}}
                ]}"""))
                .isInstanceOf(ConfigurationException.class);
    }

    @Test
    @DisplayName("--config zeigt auf eine konkrete Datei")
    void loadsFileGivenOnCommandLine(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("custom.json");
        Files.writeString(file, """
                {"endpoints": [{"port": 8081, "method": "GET", "path": "/x", "response": {}}]}""");

        EndpointConfig config = loader.load(new String[]{"--config=" + file});

        assertThat(config.endpoints()).hasSize(1);
        assertThat(config.endpoints().get(0).port()).isEqualTo(8081);
    }

    @Test
    @DisplayName("Eine fehlende Datei aus --config bricht den Start ab")
    void failsForMissingConfiguredFile(@TempDir Path dir) {
        assertThatThrownBy(() -> loader.load(new String[]{"--config=" + dir.resolve("fehlt.json")}))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("nicht gefunden");
    }

    @Test
    @DisplayName("Die mitgelieferte files/endpoints.json ist gueltig")
    void shippedExampleConfigurationIsValid() {
        Path example = Path.of(EndpointConfigLoader.DEFAULT_FILE);
        assertThat(Files.isRegularFile(example)).isTrue();

        EndpointRegistry registry = EndpointRegistry.of(loader.loadFrom(example));

        assertThat(registry.ports()).containsExactly(8080, 9090);
    }
}
