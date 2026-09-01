# Chrystalis Mock-Server

Ein Java-/Maven-/Spring-Boot-Webserver, dessen Endpunkte **nicht im Code stehen**, sondern in
einer JSON-Datei beschrieben werden. Jeder Eintrag definiert Endpunkt, Port, erwarteten Payload
und den Rückgabewert. JSON anpassen, neu starten – kein Code-Änderung nötig.

Eingabedatei: [`files/endpoints.json`](files/endpoints.json)

## Schnellstart

```bash
mvn clean verify          # baut und testet
mvn spring-boot:run       # startet den Server (Beispielkonfiguration: Ports 8080 und 9090)
```

Als ausführbares Jar:

```bash
mvn clean package
java -jar target/chrystalis-mockserver.jar --config=files/endpoints.json
```

Beispiel-Requests gegen die mitgelieferte Konfiguration:

```bash
# 201 – Payload passt (zusätzliche Felder sind erlaubt)
curl -i -X POST localhost:8080/api/users \
  -H 'Content-Type: application/json' \
  -d '{"user":{"name":"Ada","role":"admin","extra":1}}'

# 400 – Payload weicht ab, die Antwort nennt den Pfad der Abweichung
curl -i -X POST localhost:8080/api/users \
  -H 'Content-Type: application/json' \
  -d '{"user":{"name":"Ada","role":"guest"}}'

curl -i localhost:8080/api/health      # 200 {"status":"UP"}
curl -i localhost:8080/api/users/42    # 200 – Ant-Muster /api/users/*
curl -i localhost:9090/ping            # 200 text/plain "pong"
curl -i localhost:8080/ping            # 404 – nur auf Port 9090 definiert
curl -i -X DELETE localhost:8080/api/health   # 405 + Allow: GET
```

## Aufbau der Konfiguration

```json
{
  "endpoints": [
    {
      "name": "createUser",
      "port": 8080,
      "method": "POST",
      "path": "/api/users",
      "expectedPayload": { "user": { "name": "Ada", "role": "admin" } },
      "response": {
        "status": 201,
        "headers": { "Content-Type": "application/json" },
        "body": { "id": 42, "status": "CREATED" }
      }
    }
  ]
}
```

| Feld | Pflicht | Bedeutung |
| --- | --- | --- |
| `name` | nein | Name für Logs und Fehlermeldungen; Standard ist `METHODE Pfad` |
| `port` | ja | Port, auf dem dieser Endpunkt erreichbar ist (1–65535) |
| `method` | ja | `GET`, `POST`, `PUT`, `PATCH`, `DELETE`, `HEAD`, `OPTIONS` oder `TRACE` |
| `path` | ja | Pfad ab `/`; Ant-Muster wie `/api/users/*` oder `/api/users/{id}` sind erlaubt |
| `expectedPayload` | nein | erwarteter Request-Body; fehlt das Feld, wird der Body nicht geprüft |
| `response.status` | nein | HTTP-Status, Standard `200` |
| `response.headers` | nein | zusätzliche Header, Standard `Content-Type: application/json` |
| `response.body` | nein | beliebiges JSON (Objekt, Array oder Skalar); fehlt es, ist der Body leer |

Ein abschließender `/` im Pfad wird ignoriert (`/api/users` und `/api/users/` sind derselbe
Endpunkt). Unbekannte Felder, doppelte Endpunkte auf demselben Port, ungültige Ports oder
Methoden führen zu einem Abbruch beim Start – mit einer Meldung, die alle gefundenen Fehler
gesammelt aufzählt.

## Mehrere Ports

Endpunkte werden nach `port` gruppiert. Der kleinste Port wird zum Haupt-Connector von Spring
Boot, für jeden weiteren legt `MultiPortConnectorCustomizer` einen zusätzlichen Tomcat-Connector
an. Die Zuordnung bleibt strikt port-lokal: ein auf Port 9090 definierter Pfad liefert auf Port
8080 ein `404`.

## Payload-Prüfung (Subset-Match)

Alles, was in `expectedPayload` steht, muss im Request vorkommen und übereinstimmen –
zusätzliche Felder im Request sind erlaubt.

- **Objekte:** jedes erwartete Feld muss existieren und rekursiv passen
- **Arrays:** gleiche Länge, elementweiser Vergleich an derselben Position
- **Zahlen:** numerischer Vergleich, `1` und `1.0` gelten als gleich
- **Typwechsel** (z. B. Objekt erwartet, Text erhalten) ist eine Abweichung

Bei einer Abweichung antwortet der Server mit `400` und benennt jede Fundstelle per JSON-Pfad:

```json
{
  "error": "PAYLOAD_MISMATCH",
  "message": "Der Request-Body entspricht nicht dem erwarteten Payload von \"createUser\".",
  "port": 8080,
  "method": "POST",
  "path": "/api/users",
  "endpoint": "createUser",
  "mismatches": [
    { "path": "$.user.role", "expected": "admin", "actual": "guest" }
  ]
}
```

## Fehlercodes

| Status | `error` | Wann |
| --- | --- | --- |
| 404 | `NO_MATCHING_ENDPOINT` | kein Endpunkt für diesen Port und Pfad |
| 405 | `METHOD_NOT_ALLOWED` | Pfad ist konfiguriert, die Methode nicht (Antwort enthält `Allow`) |
| 400 | `INVALID_JSON_BODY` | ein Payload wird erwartet, der Body ist aber kein gültiges JSON |
| 400 | `PAYLOAD_MISMATCH` | der Body entspricht nicht dem erwarteten Payload |

## Speicherort der Konfiguration

Der erste Treffer dieser Reihenfolge gewinnt:

1. `--config=<pfad>` als Kommandozeilenargument
2. System-Property `-Dmockserver.config=<pfad>`
3. Umgebungsvariable `MOCKSERVER_CONFIG`
4. `files/endpoints.json` relativ zum Arbeitsverzeichnis (Standard)
5. `endpoints.json` im Classpath

## Projektstruktur

```
files/endpoints.json                     Eingabe-Konfiguration
src/main/java/com/chrystalis/mockserver/
  MockServerApplication.java             main(): Konfiguration laden, dann starten
  MockServerBootstrap.java               setzt server.port und reicht die Registry hinein
  config/                                Konfigurationsmodell, Laden, Validierung, Lookup
  match/PayloadMatcher.java              Subset-Vergleich mit JSON-Pfaden
  web/DynamicEndpointController.java     Catch-all /** – Dispatch nach Port, Pfad und Methode
  web/MultiPortConnectorCustomizer.java  zusätzliche Tomcat-Connectors
```

## Tests

```bash
mvn test
```

- `PayloadMatcherTest` – Subset-Semantik, Arrays, Zahlen, Typwechsel, Mismatch-Pfade
- `EndpointConfigLoaderTest` – Parsen, Defaults, Validierungsfehler, Pfadauflösung
- `EndpointRegistryTest` – Port-Isolation, exakte Pfade vor Mustern, erlaubte Methoden
- `MultiPortIntegrationTest` – startet den echten Server auf zwei freien Ports und prüft
  201/400/404/405 sowie `text/plain` über echte HTTP-Requests
