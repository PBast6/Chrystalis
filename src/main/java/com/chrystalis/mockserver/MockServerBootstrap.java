package com.chrystalis.mockserver;

import com.chrystalis.mockserver.config.EndpointDefinition;
import com.chrystalis.mockserver.config.EndpointRegistry;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Startet die Anwendung mit einer bereits geladenen {@link EndpointRegistry}.
 *
 * <p>Die Registry wird vor dem Start als Singleton registriert, weil der primaere Port als
 * {@code server.port} feststehen muss, bevor Tomcat hochfaehrt. Denselben Weg nutzen die
 * Integrationstests, damit sie exakt den Produktionsstart pruefen.
 */
public final class MockServerBootstrap {

    public static final String REGISTRY_BEAN_NAME = "endpointRegistry";

    private MockServerBootstrap() {
    }

    public static SpringApplication application(EndpointRegistry registry) {
        SpringApplication application = new SpringApplication(MockServerApplication.class);
        Map<String, Object> defaults = new LinkedHashMap<>();
        defaults.put("server.port", registry.primaryPort());
        application.setDefaultProperties(defaults);
        application.addInitializers(context ->
                context.getBeanFactory().registerSingleton(REGISTRY_BEAN_NAME, registry));
        return application;
    }

    public static ConfigurableApplicationContext run(EndpointRegistry registry, String... args) {
        return application(registry).run(args);
    }

    /**
     * Kurze Uebersicht der konfigurierten Endpunkte fuer das Startlog.
     */
    public static String describe(EndpointRegistry registry) {
        StringBuilder text = new StringBuilder("Konfigurierte Endpunkte:");
        for (Integer port : registry.ports()) {
            text.append(System.lineSeparator()).append("  Port ").append(port).append(':');
            for (EndpointDefinition endpoint : registry.endpoints()) {
                if (endpoint.port().equals(port)) {
                    text.append(System.lineSeparator())
                            .append("    ").append(endpoint.method()).append(' ').append(endpoint.path())
                            .append(" -> ").append(endpoint.response().statusOrDefault())
                            .append(endpoint.hasExpectedPayload() ? " (Payload wird geprueft)" : "")
                            .append("  [").append(endpoint.displayName()).append(']');
                }
            }
        }
        return text.toString();
    }
}
