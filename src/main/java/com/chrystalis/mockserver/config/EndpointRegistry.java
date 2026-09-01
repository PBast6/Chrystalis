package com.chrystalis.mockserver.config;

import org.springframework.util.AntPathMatcher;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Haelt die konfigurierten Endpunkte nach Port gruppiert und beantwortet die Frage, welcher
 * Endpunkt einen konkreten Request bedient.
 *
 * <p>Die Suche ist bewusst port-lokal: ein auf Port 9090 definierter Pfad ist auf Port 8080
 * nicht erreichbar. Exakte Pfade gewinnen gegen Ant-Muster wie {@code /api/users/*}.
 */
public class EndpointRegistry {

    /** Ergebnis einer Suche: Treffer, Methode nicht erlaubt oder gar nichts gefunden. */
    public enum Status { MATCHED, METHOD_NOT_ALLOWED, NOT_FOUND }

    /**
     * @param status         Ausgang der Suche
     * @param endpoint       gefundener Endpunkt, nur bei {@link Status#MATCHED} gesetzt
     * @param allowedMethods bei {@link Status#METHOD_NOT_ALLOWED} die auf dem Pfad definierten Methoden
     */
    public record Lookup(Status status, EndpointDefinition endpoint, Set<String> allowedMethods) {

        static Lookup matched(EndpointDefinition endpoint) {
            return new Lookup(Status.MATCHED, endpoint, Set.of());
        }

        static Lookup methodNotAllowed(Set<String> allowedMethods) {
            return new Lookup(Status.METHOD_NOT_ALLOWED, null, allowedMethods);
        }

        static Lookup notFound() {
            return new Lookup(Status.NOT_FOUND, null, Set.of());
        }
    }

    private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();

    private final List<EndpointDefinition> endpoints;
    private final Map<Integer, PortRoutes> routesByPort;

    public EndpointRegistry(List<EndpointDefinition> endpoints) {
        if (endpoints == null || endpoints.isEmpty()) {
            throw new ConfigurationException("Es ist kein Endpunkt konfiguriert.");
        }
        this.endpoints = List.copyOf(endpoints);
        Map<Integer, PortRoutes> byPort = new TreeMap<>();
        for (EndpointDefinition endpoint : this.endpoints) {
            byPort.computeIfAbsent(endpoint.port(), port -> new PortRoutes()).add(endpoint);
        }
        this.routesByPort = byPort;
    }

    public static EndpointRegistry of(EndpointConfig config) {
        return new EndpointRegistry(config.endpoints());
    }

    public List<EndpointDefinition> endpoints() {
        return endpoints;
    }

    /** Alle konfigurierten Ports, aufsteigend sortiert. */
    public List<Integer> ports() {
        return List.copyOf(routesByPort.keySet());
    }

    /** Der Port, auf dem Spring Boot seinen Haupt-Connector oeffnet. */
    public int primaryPort() {
        return ports().get(0);
    }

    /** Alle Ports ausser dem primaeren; fuer diese werden zusaetzliche Connectors angelegt. */
    public List<Integer> additionalPorts() {
        List<Integer> ports = new ArrayList<>(ports());
        ports.remove(0);
        return List.copyOf(ports);
    }

    /**
     * Sucht den Endpunkt, der {@code method path} auf {@code port} bedient.
     */
    public Lookup lookup(int port, String method, String path) {
        PortRoutes routes = routesByPort.get(port);
        if (routes == null) {
            return Lookup.notFound();
        }
        String normalizedPath = EndpointConfigLoader.normalizePath(path);
        List<EndpointDefinition> candidates = routes.candidates(normalizedPath);
        if (candidates.isEmpty()) {
            return Lookup.notFound();
        }

        String normalizedMethod = method == null ? "" : method.toUpperCase();
        for (EndpointDefinition candidate : candidates) {
            if (candidate.method().equals(normalizedMethod)) {
                return Lookup.matched(candidate);
            }
        }

        Set<String> allowed = new LinkedHashSet<>();
        candidates.forEach(candidate -> allowed.add(candidate.method()));
        return Lookup.methodNotAllowed(allowed);
    }

    /** Die Endpunkte eines Ports: exakte Pfade getrennt von Ant-Mustern. */
    private static final class PortRoutes {

        private final Map<String, List<EndpointDefinition>> exact = new LinkedHashMap<>();
        private final List<EndpointDefinition> patterns = new ArrayList<>();

        void add(EndpointDefinition endpoint) {
            if (PATH_MATCHER.isPattern(endpoint.path())) {
                patterns.add(endpoint);
            } else {
                exact.computeIfAbsent(endpoint.path(), path -> new ArrayList<>()).add(endpoint);
            }
        }

        /** Alle fuer diesen Pfad in Frage kommenden Endpunkte, spezifischste zuerst. */
        List<EndpointDefinition> candidates(String path) {
            List<EndpointDefinition> result = new ArrayList<>(exact.getOrDefault(path, List.of()));
            if (!patterns.isEmpty()) {
                Comparator<String> bySpecificity = PATH_MATCHER.getPatternComparator(path);
                patterns.stream()
                        .filter(endpoint -> PATH_MATCHER.match(endpoint.path(), path))
                        .sorted(Comparator.comparing(EndpointDefinition::path, bySpecificity))
                        .forEach(result::add);
            }
            return result;
        }
    }
}
