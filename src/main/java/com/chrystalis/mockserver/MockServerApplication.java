package com.chrystalis.mockserver;

import com.chrystalis.mockserver.config.ConfigurationException;
import com.chrystalis.mockserver.config.EndpointConfig;
import com.chrystalis.mockserver.config.EndpointConfigLoader;
import com.chrystalis.mockserver.config.EndpointRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Mock-Webserver, dessen Endpunkte aus einer JSON-Datei stammen.
 *
 * <p>Start: {@code mvn spring-boot:run} oder
 * {@code java -jar target/chrystalis-mockserver.jar --config=files/endpoints.json}.
 */
@SpringBootApplication
public class MockServerApplication {

    private static final Logger log = LoggerFactory.getLogger(MockServerApplication.class);

    public static void main(String[] args) {
        EndpointRegistry registry;
        try {
            EndpointConfig config = new EndpointConfigLoader().load(args);
            registry = EndpointRegistry.of(config);
        } catch (ConfigurationException e) {
            log.error("Start abgebrochen: {}", e.getMessage());
            System.exit(1);
            return;
        }

        log.info("{}", MockServerBootstrap.describe(registry));
        MockServerBootstrap.run(registry, args);
    }
}
