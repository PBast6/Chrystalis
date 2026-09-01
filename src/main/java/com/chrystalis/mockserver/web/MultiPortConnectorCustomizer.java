package com.chrystalis.mockserver.web;

import com.chrystalis.mockserver.config.EndpointRegistry;
import org.apache.catalina.connector.Connector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.stereotype.Component;

/**
 * Oeffnet fuer jeden in der Konfiguration genannten Port einen HTTP-Connector. Der erste Port
 * wird beim Start als {@code server.port} gesetzt, alle weiteren kommen hier dazu.
 *
 * <p>Alle Connectors teilen sich denselben Servlet-Kontext; die Trennung nach Port passiert im
 * {@link DynamicEndpointController} anhand von {@code request.getLocalPort()}.
 */
@Component
public class MultiPortConnectorCustomizer implements WebServerFactoryCustomizer<TomcatServletWebServerFactory> {

    private static final String HTTP_PROTOCOL = "org.apache.coyote.http11.Http11NioProtocol";
    private static final Logger log = LoggerFactory.getLogger(MultiPortConnectorCustomizer.class);

    private final EndpointRegistry registry;

    public MultiPortConnectorCustomizer(EndpointRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void customize(TomcatServletWebServerFactory factory) {
        for (Integer port : registry.additionalPorts()) {
            Connector connector = new Connector(HTTP_PROTOCOL);
            connector.setPort(port);
            factory.addAdditionalTomcatConnectors(connector);
            log.info("Zusaetzlicher Connector auf Port {} konfiguriert", port);
        }
    }
}
