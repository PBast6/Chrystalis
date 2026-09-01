package com.chrystalis.mockserver.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EndpointRegistryTest {

    private static EndpointDefinition endpoint(String name, int port, String method, String path) {
        return new EndpointDefinition(name, port, method, path, null,
                new ResponseDefinition(null, null, null));
    }

    private static EndpointRegistry registry(EndpointDefinition... endpoints) {
        return new EndpointRegistry(List.of(endpoints));
    }

    @Test
    @DisplayName("Ports werden sortiert; der kleinste ist der primaere Port")
    void exposesPortsInAscendingOrder() {
        EndpointRegistry registry = registry(
                endpoint("b", 9090, "GET", "/b"),
                endpoint("a", 8080, "GET", "/a"));

        assertThat(registry.ports()).containsExactly(8080, 9090);
        assertThat(registry.primaryPort()).isEqualTo(8080);
        assertThat(registry.additionalPorts()).containsExactly(9090);
    }

    @Test
    @DisplayName("Ein Endpunkt eines anderen Ports ist nicht erreichbar")
    void endpointsAreIsolatedPerPort() {
        EndpointRegistry registry = registry(
                endpoint("ping", 9090, "GET", "/ping"),
                endpoint("health", 8080, "GET", "/health"));

        assertThat(registry.lookup(9090, "GET", "/ping").status())
                .isEqualTo(EndpointRegistry.Status.MATCHED);
        assertThat(registry.lookup(8080, "GET", "/ping").status())
                .isEqualTo(EndpointRegistry.Status.NOT_FOUND);
        assertThat(registry.lookup(7070, "GET", "/ping").status())
                .isEqualTo(EndpointRegistry.Status.NOT_FOUND);
    }

    @Test
    @DisplayName("Ein abschliessender Schraegstrich trifft denselben Endpunkt")
    void trailingSlashIsIgnored() {
        EndpointRegistry registry = registry(endpoint("health", 8080, "GET", "/api/health"));

        assertThat(registry.lookup(8080, "GET", "/api/health/").status())
                .isEqualTo(EndpointRegistry.Status.MATCHED);
    }

    @Test
    @DisplayName("Die falsche Methode liefert die erlaubten Methoden des Pfads")
    void reportsAllowedMethods() {
        EndpointRegistry registry = registry(
                endpoint("get", 8080, "GET", "/x"),
                endpoint("post", 8080, "POST", "/x"));

        EndpointRegistry.Lookup lookup = registry.lookup(8080, "DELETE", "/x");

        assertThat(lookup.status()).isEqualTo(EndpointRegistry.Status.METHOD_NOT_ALLOWED);
        assertThat(lookup.allowedMethods()).containsExactlyInAnyOrder("GET", "POST");
    }

    @Test
    @DisplayName("Ant-Muster greifen, exakte Pfade haben aber Vorrang")
    void exactPathWinsOverPattern() {
        EndpointDefinition exact = endpoint("exact", 8080, "GET", "/api/users/me");
        EndpointDefinition pattern = endpoint("pattern", 8080, "GET", "/api/users/*");
        EndpointRegistry registry = registry(pattern, exact);

        assertThat(registry.lookup(8080, "GET", "/api/users/me").endpoint()).isEqualTo(exact);
        assertThat(registry.lookup(8080, "GET", "/api/users/42").endpoint()).isEqualTo(pattern);
    }

    @Test
    @DisplayName("Ein Muster kann eine Methode beisteuern, die der exakte Pfad nicht kennt")
    void patternContributesMethodsForMethodNotAllowed() {
        EndpointRegistry registry = registry(
                endpoint("exact", 8080, "GET", "/api/users/me"),
                endpoint("pattern", 8080, "POST", "/api/users/*"));

        assertThat(registry.lookup(8080, "POST", "/api/users/me").endpoint().name()).isEqualTo("pattern");
        assertThat(registry.lookup(8080, "DELETE", "/api/users/me").allowedMethods())
                .containsExactlyInAnyOrder("GET", "POST");
    }

    @Test
    @DisplayName("Platzhalter im Stil von {id} werden unterstuetzt")
    void supportsUriTemplateStylePatterns() {
        EndpointRegistry registry = registry(endpoint("byId", 8080, "GET", "/api/users/{id}"));

        assertThat(registry.lookup(8080, "GET", "/api/users/42").status())
                .isEqualTo(EndpointRegistry.Status.MATCHED);
        assertThat(registry.lookup(8080, "GET", "/api/users/42/orders").status())
                .isEqualTo(EndpointRegistry.Status.NOT_FOUND);
    }

    @Test
    @DisplayName("Die Methode wird unabhaengig von der Schreibweise verglichen")
    void methodComparisonIsCaseInsensitive() {
        EndpointRegistry registry = registry(endpoint("health", 8080, "GET", "/x"));

        assertThat(registry.lookup(8080, "get", "/x").status())
                .isEqualTo(EndpointRegistry.Status.MATCHED);
    }
}
