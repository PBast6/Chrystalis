package com.chrystalis.mockserver.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Laedt die Endpunkt-Konfiguration, prueft sie und bricht bei Fehlern sofort ab, damit der
 * Server nie halb konfiguriert startet.
 *
 * <p>Der Speicherort wird in dieser Reihenfolge bestimmt:
 * <ol>
 *   <li>Kommandozeilenargument {@code --config=<pfad>}</li>
 *   <li>System-Property {@code mockserver.config}</li>
 *   <li>Umgebungsvariable {@code MOCKSERVER_CONFIG}</li>
 *   <li>{@code files/endpoints.json} relativ zum Arbeitsverzeichnis</li>
 *   <li>{@code endpoints.json} im Classpath</li>
 * </ol>
 */
public class EndpointConfigLoader {

    public static final String DEFAULT_FILE = "files/endpoints.json";
    public static final String CLASSPATH_FALLBACK = "endpoints.json";
    public static final String CONFIG_PROPERTY = "mockserver.config";
    public static final String CONFIG_ENV = "MOCKSERVER_CONFIG";

    private static final String CLI_PREFIX = "--config=";
    private static final Set<String> KNOWN_METHODS =
            Set.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS", "TRACE");

    private static final Logger log = LoggerFactory.getLogger(EndpointConfigLoader.class);

    private final ObjectMapper mapper;

    public EndpointConfigLoader() {
        this(new ObjectMapper());
    }

    public EndpointConfigLoader(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * Laedt die Konfiguration vom ersten Ort, der laut Reihenfolge oben etwas liefert.
     */
    public EndpointConfig load(String[] args) {
        String explicit = explicitLocation(args);
        if (explicit != null) {
            Path path = Path.of(explicit);
            if (!Files.isRegularFile(path)) {
                throw new ConfigurationException(
                        "Konfigurationsdatei nicht gefunden: " + path.toAbsolutePath());
            }
            return loadFrom(path);
        }

        Path defaultPath = Path.of(DEFAULT_FILE);
        if (Files.isRegularFile(defaultPath)) {
            return loadFrom(defaultPath);
        }

        try (InputStream in = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream(CLASSPATH_FALLBACK)) {
            if (in != null) {
                log.info("Lade Endpunkt-Konfiguration aus dem Classpath: {}", CLASSPATH_FALLBACK);
                return parse(in.readAllBytes(), "classpath:" + CLASSPATH_FALLBACK);
            }
        } catch (IOException e) {
            throw new ConfigurationException(
                    "Konfiguration konnte nicht aus dem Classpath gelesen werden: " + CLASSPATH_FALLBACK, e);
        }

        throw new ConfigurationException(
                "Keine Endpunkt-Konfiguration gefunden. Erwartet wurde " + defaultPath.toAbsolutePath()
                        + ", ein Classpath-Eintrag " + CLASSPATH_FALLBACK
                        + " oder eine Angabe via --config=<pfad>, -D" + CONFIG_PROPERTY
                        + " bzw. " + CONFIG_ENV + ".");
    }

    /**
     * Laedt und prueft die Konfiguration aus einer konkreten Datei.
     */
    public EndpointConfig loadFrom(Path path) {
        byte[] content;
        try {
            content = Files.readAllBytes(path);
        } catch (IOException e) {
            throw new ConfigurationException(
                    "Konfigurationsdatei konnte nicht gelesen werden: " + path.toAbsolutePath(), e);
        }
        log.info("Lade Endpunkt-Konfiguration aus {}", path.toAbsolutePath());
        return parse(content, path.toAbsolutePath().toString());
    }

    /**
     * Parst und prueft eine Konfiguration; die zurueckgegebenen Endpunkte sind normalisiert
     * (Methode in Grossbuchstaben, Pfad ohne abschliessenden Schraegstrich).
     */
    public EndpointConfig parse(byte[] content, String source) {
        EndpointConfig config;
        try {
            config = mapper.readValue(content, EndpointConfig.class);
        } catch (JsonProcessingException e) {
            throw new ConfigurationException(
                    "Konfiguration ist kein gueltiges JSON (" + source + "): " + e.getOriginalMessage(), e);
        } catch (IOException e) {
            throw new ConfigurationException("Konfiguration konnte nicht gelesen werden: " + source, e);
        }

        if (config == null || config.endpoints() == null || config.endpoints().isEmpty()) {
            throw new ConfigurationException(
                    "Konfiguration enthaelt keine Endpunkte (" + source + "). Erwartet wird ein Objekt "
                            + "mit dem Feld \"endpoints\".");
        }
        return new EndpointConfig(validate(config.endpoints(), source));
    }

    private List<EndpointDefinition> validate(List<EndpointDefinition> endpoints, String source) {
        List<String> errors = new ArrayList<>();
        List<EndpointDefinition> normalized = new ArrayList<>(endpoints.size());
        Map<String, String> seen = new HashMap<>();

        for (int i = 0; i < endpoints.size(); i++) {
            EndpointDefinition endpoint = endpoints.get(i);
            String where = "endpoints[" + i + "]"
                    + (endpoint != null && endpoint.name() != null ? " (" + endpoint.name() + ")" : "");

            if (endpoint == null) {
                errors.add(where + ": Eintrag ist null");
                continue;
            }

            Integer port = endpoint.port();
            if (port == null) {
                errors.add(where + ": Feld \"port\" fehlt");
            } else if (port < 1 || port > 65535) {
                errors.add(where + ": Port " + port + " liegt nicht zwischen 1 und 65535");
            }

            String method = endpoint.method() == null ? null : endpoint.method().trim().toUpperCase();
            if (method == null || method.isEmpty()) {
                errors.add(where + ": Feld \"method\" fehlt");
            } else if (!KNOWN_METHODS.contains(method)) {
                errors.add(where + ": unbekannte HTTP-Methode \"" + endpoint.method() + "\", erlaubt sind "
                        + new LinkedHashSet<>(KNOWN_METHODS));
            }

            String path = endpoint.path();
            if (path == null || path.isBlank()) {
                errors.add(where + ": Feld \"path\" fehlt");
            } else if (!path.startsWith("/")) {
                errors.add(where + ": Pfad \"" + path + "\" muss mit \"/\" beginnen");
            }

            if (endpoint.response() == null) {
                errors.add(where + ": Feld \"response\" fehlt");
            } else {
                Integer status = endpoint.response().status();
                if (status != null && (status < 100 || status > 599)) {
                    errors.add(where + ": Statuscode " + status + " liegt nicht zwischen 100 und 599");
                }
            }

            if (port == null || method == null || method.isEmpty() || path == null || path.isBlank()) {
                continue;
            }

            String normalizedPath = normalizePath(path);
            EndpointDefinition normalizedEndpoint = new EndpointDefinition(
                    endpoint.name(), port, method, normalizedPath,
                    endpoint.expectedPayload(), endpoint.response());

            String key = port + " " + method + " " + normalizedPath;
            String previous = seen.putIfAbsent(key, normalizedEndpoint.displayName());
            if (previous != null) {
                errors.add(where + ": doppelte Definition von " + method + " " + normalizedPath
                        + " auf Port " + port + " (bereits definiert durch \"" + previous + "\")");
                continue;
            }
            normalized.add(normalizedEndpoint);
        }

        if (!errors.isEmpty()) {
            throw new ConfigurationException("Ungueltige Endpunkt-Konfiguration (" + source + "):"
                    + System.lineSeparator() + "  - " + String.join(System.lineSeparator() + "  - ", errors));
        }
        return List.copyOf(normalized);
    }

    /**
     * Entfernt einen abschliessenden Schraegstrich, damit {@code /api/users} und {@code /api/users/}
     * denselben Endpunkt treffen. Der Wurzelpfad {@code /} bleibt unveraendert.
     */
    public static String normalizePath(String path) {
        if (path == null || path.isEmpty()) {
            return "/";
        }
        String result = path.startsWith("/") ? path : "/" + path;
        while (result.length() > 1 && result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private static String explicitLocation(String[] args) {
        if (args != null) {
            for (String arg : args) {
                if (arg != null && arg.startsWith(CLI_PREFIX)) {
                    String value = arg.substring(CLI_PREFIX.length()).trim();
                    if (!value.isEmpty()) {
                        return value;
                    }
                }
            }
        }
        String property = System.getProperty(CONFIG_PROPERTY);
        if (property != null && !property.isBlank()) {
            return property.trim();
        }
        String env = System.getenv(CONFIG_ENV);
        if (env != null && !env.isBlank()) {
            return env.trim();
        }
        return null;
    }
}
