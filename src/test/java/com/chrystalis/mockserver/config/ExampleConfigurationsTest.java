package com.chrystalis.mockserver.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Haelt die ausgelieferten Konfigurationen, das JSON-Schema und die Java-Validierung zusammen:
 * jede Datei unter {@code files/} muss sowohl das Schema erfuellen als auch vom Loader
 * akzeptiert werden.
 */
class ExampleConfigurationsTest {

    private static final Path SCHEMA_FILE = Path.of("files/endpoints.schema.json");
    private static final Path MAIN_CONFIG = Path.of("files/endpoints.json");
    private static final Path EXAMPLES_DIR = Path.of("files/examples");

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Schema SCHEMA = loadSchema();

    private final EndpointConfigLoader loader = new EndpointConfigLoader();

    private static Schema loadSchema() {
        try {
            return SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
                    .getSchema(Files.readString(SCHEMA_FILE, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("Schema nicht lesbar: " + SCHEMA_FILE.toAbsolutePath(), e);
        }
    }

    /** Die Hauptkonfiguration und jede Beispieldatei. */
    static Stream<Path> shippedConfigurations() throws IOException {
        try (var files = Files.list(EXAMPLES_DIR)) {
            List<Path> examples = files
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .sorted()
                    .toList();
            assertThat(examples).as("Beispielkonfigurationen unter " + EXAMPLES_DIR).isNotEmpty();
            return Stream.concat(Stream.of(MAIN_CONFIG), examples.stream());
        }
    }

    private static List<Error> validateAgainstSchema(Path file) throws IOException {
        JsonNode content = MAPPER.readTree(Files.readString(file, StandardCharsets.UTF_8));
        return SCHEMA.validate(content);
    }

    @ParameterizedTest(name = "{0} erfuellt das JSON-Schema")
    @MethodSource("shippedConfigurations")
    void everyShippedConfigurationMatchesTheSchema(Path file) throws IOException {
        assertThat(validateAgainstSchema(file))
                .as("Schema-Verstoesse in " + file)
                .isEmpty();
    }

    @ParameterizedTest(name = "{0} laedt und ergibt eine gueltige Registry")
    @MethodSource("shippedConfigurations")
    void everyShippedConfigurationLoads(Path file) {
        EndpointRegistry registry = EndpointRegistry.of(loader.loadFrom(file));

        assertThat(registry.endpoints()).isNotEmpty();
        assertThat(registry.ports()).isNotEmpty();
        assertThat(registry.primaryPort()).isEqualTo(registry.ports().get(0));
        registry.endpoints().forEach(endpoint -> {
            assertThat(endpoint.path()).startsWith("/");
            assertThat(endpoint.method()).isUpperCase();
            assertThat(endpoint.response()).isNotNull();
        });
    }

    @ParameterizedTest(name = "{0} verweist auf das Schema")
    @MethodSource("shippedConfigurations")
    void everyShippedConfigurationReferencesTheSchema(Path file) throws IOException {
        JsonNode content = MAPPER.readTree(Files.readString(file, StandardCharsets.UTF_8));

        assertThat(content.has("$schema"))
                .as(file + " sollte per \"$schema\" auf endpoints.schema.json verweisen")
                .isTrue();
        assertThat(content.get("$schema").asText()).endsWith("endpoints.schema.json");
    }

    @Test
    @DisplayName("Das Schema lehnt dieselben Fehler ab wie der Loader")
    void schemaAndLoaderRejectTheSameMistakes() throws IOException {
        String broken = """
                {"endpoints": [
                  {"port": 70000, "method": "FETCH", "path": "api/x"}
                ]}""";

        List<Error> violations = SCHEMA.validate(MAPPER.readTree(broken));
        assertThat(violations).isNotEmpty();
        assertThat(violations.toString())
                .contains("port")
                .contains("method")
                .contains("path")
                .contains("response");

        assertThatThrownBy(() -> loader.parse(broken.getBytes(StandardCharsets.UTF_8), "test"))
                .isInstanceOf(ConfigurationException.class);
    }

    @Test
    @DisplayName("Das Schema lehnt unbekannte Felder ab, genau wie der Loader")
    void schemaRejectsUnknownFields() throws IOException {
        String typo = """
                {"endpoints": [
                  {"port": 8080, "method": "GET", "path": "/x", "respones": {}}
                ]}""";

        assertThat(SCHEMA.validate(MAPPER.readTree(typo))).isNotEmpty();
        assertThatThrownBy(() -> loader.parse(typo.getBytes(StandardCharsets.UTF_8), "test"))
                .isInstanceOf(ConfigurationException.class);
    }

    @Test
    @DisplayName("Das Schema erlaubt $schema, sonst waeren die Beispieldateien ungueltig")
    void schemaAllowsSchemaReference() throws IOException {
        String withReference = """
                {"$schema": "endpoints.schema.json",
                 "endpoints": [{"port": 8080, "method": "GET", "path": "/x", "response": {}}]}""";

        assertThat(SCHEMA.validate(MAPPER.readTree(withReference))).isEmpty();
    }
}
