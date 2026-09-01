# Konfigurationsreferenz

Der Mock-Server liest seine Endpunkte aus einer JSON-Datei. Diese Seite beschreibt jedes Feld,
jeden Default und jede Fehlermeldung. Lauffähige Beispiele stehen in
[`files/examples/`](../files/examples/README.md).

## Aufbau in einem Blick

Die Datei ist ein Objekt mit einer Liste `endpoints`. Jeder Eintrag beantwortet vier Fragen:
**Wo** (Port + Pfad + Methode), **was muss reinkommen** (`expectedPayload`) und **was kommt
zurück** (`response`).

```
{
  "$schema": "endpoints.schema.json",     ← optional, nur für die IDE
  "endpoints": [
    {
      "name": "createUser",               ← optional, erscheint in Logs und Fehlern
      "port": 8080,                       ← Pflicht  ┐
      "method": "POST",                   ← Pflicht  ├ zusammen eindeutig
      "path": "/api/users",               ← Pflicht  ┘
      "expectedPayload": {                ← optional: fehlt es, wird der Body nicht geprüft
        "user": { "name": "Ada" }
      },
      "response": {                       ← Pflicht
        "status": 201,                    ← optional, Default 200
        "headers": {                      ← optional, Default Content-Type: application/json
          "Content-Type": "application/json"
        },
        "body": { "id": 42 }              ← optional, beliebiges JSON
      }
    }
  ]
}
```

Als gültige JSON-Datei (JSON kennt keine Kommentare):

```json
{
  "$schema": "endpoints.schema.json",
  "endpoints": [
    {
      "name": "createUser",
      "port": 8080,
      "method": "POST",
      "path": "/api/users",
      "expectedPayload": { "user": { "name": "Ada" } },
      "response": {
        "status": 201,
        "headers": { "Content-Type": "application/json" },
        "body": { "id": 42 }
      }
    }
  ]
}
```

## Felder

### Oberste Ebene

| Feld | Typ | Pflicht | Bedeutung |
| --- | --- | --- | --- |
| `$schema` | String | nein | Verweis auf `endpoints.schema.json`, damit die IDE validiert und vervollständigt. Der Server ignoriert das Feld — es ist das einzige geduldete Zusatzfeld. |
| `endpoints` | Array | **ja** | Mindestens ein Endpunkt. |

### Ein Endpunkt

| Feld | Typ | Pflicht | Default | Bedeutung |
| --- | --- | --- | --- | --- |
| `name` | String | nein | `"METHODE Pfad"` | Erscheint im Startlog, in Log-Zeilen je Request und im Feld `endpoint` von Fehlerantworten. |
| `port` | Zahl | **ja** | – | 1–65535. Der kleinste Port der Datei wird `server.port`, für jeden weiteren wird ein zusätzlicher Tomcat-Connector geöffnet. |
| `method` | String | **ja** | – | `GET`, `POST`, `PUT`, `PATCH`, `DELETE`, `HEAD`, `OPTIONS`, `TRACE`. Wird beim Laden in Großbuchstaben normalisiert, `"get"` ist also erlaubt. |
| `path` | String | **ja** | – | Beginnt mit `/`. Ant-Muster erlaubt (siehe unten). Ein abschließender `/` wird beim Laden **und** bei jedem Request entfernt. |
| `expectedPayload` | beliebiges JSON | nein | – | Erwarteter Request-Body. Fehlt das Feld, wird der Body überhaupt nicht angesehen. |
| `response` | Objekt | **ja** | – | Was zurückgegeben wird. |

### `response`

| Feld | Typ | Pflicht | Default | Bedeutung |
| --- | --- | --- | --- | --- |
| `status` | Zahl | nein | `200` | 100–599. |
| `headers` | Objekt (String → String) | nein | `{"Content-Type": "application/json"}` | Wird unverändert gesetzt. Enthält die Map einen eigenen Content-Type — auch klein geschrieben —, bleibt dieser stehen. |
| `body` | beliebiges JSON | nein | leerer Body | Objekt, Array, Text, Zahl oder Boolean. Bei einem Nicht-JSON-Content-Type wird ein Text-Body als Klartext ausgeliefert, sonst als JSON (also mit Anführungszeichen). |

## Payload-Prüfung: Subset-Match

Alles, was in `expectedPayload` steht, muss im Request vorkommen und übereinstimmen.
Zusätzliche Felder im Request sind erlaubt.

| Erwartet | Request | Ergebnis |
| --- | --- | --- |
| `{"a": 1}` | `{"a": 1, "b": 2}` | passt — Zusatzfeld erlaubt |
| `{"a": 1}` | `{"b": 2}` | `$.a` erwartet `1`, tatsächlich `<fehlt>` |
| `{"a": 1}` | `{"a": 1.0}` | passt — Zahlen werden numerisch verglichen |
| `{"a": null}` | `{"a": null}` | passt |
| `{"a": null}` | `{}` | `$.a` fehlt — ein erwartetes `null` verlangt das Feld |
| `{}` | `{"x": 1}` | passt — leeres Objekt stellt keine Bedingung |
| `{"a": {"b": 1}}` | `{"a": "text"}` | `$.a`: `Objekt {...}` erwartet, `Text text` erhalten |
| `{"l": [1, 2]}` | `{"l": [1, 2, 3]}` | `$.l`: Array mit 2 Elementen erwartet, 3 erhalten |
| `{"l": [{"s": "A"}]}` | `{"l": [{"s": "A", "q": 2}]}` | passt — Subset gilt auch je Array-Element |
| `{"l": [{"s": "A"}]}` | `{"l": [{"s": "Z"}]}` | `$.l[0].s` erwartet `A`, tatsächlich `Z` |

Alle Abweichungen werden gesammelt — die Antwort nennt nicht nur die erste:

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

## Welcher Endpunkt trifft?

Gesucht wird immer in dieser Reihenfolge:

1. **Port.** Die Suche ist port-lokal. Ein auf 9090 definierter Pfad liefert auf 8080 ein `404`,
   obwohl beide Ports derselbe Prozess sind.
2. **Pfad.** Zuerst exakte Treffer, dann Ant-Muster nach Spezifität sortiert. `/api/users/me`
   gewinnt also gegen `/api/users/*`.
   - `*` – ein Pfadsegment (`/api/users/*` trifft `/api/users/42`, nicht `/api/users/42/orders`)
   - `**` – beliebig viele Segmente (`/static/**`)
   - `{id}` – wie `*`, nur lesbarer
3. **Methode.** Passt keiner der Kandidaten, antwortet der Server mit `405` und listet im
   `Allow`-Header alle auf diesem Pfad definierten Methoden.

Die Methode wird immer wörtlich genommen: für `HEAD` braucht es einen eigenen Eintrag mit
`"method": "HEAD"` — ein konfiguriertes `GET` beantwortet keine HEAD-Requests.

## Antworten des Servers

| Status | `error` | Wann |
| --- | --- | --- |
| konfiguriert | – | Endpunkt trifft, Payload passt (oder wird nicht geprüft) |
| 400 | `PAYLOAD_MISMATCH` | Body weicht vom `expectedPayload` ab |
| 400 | `INVALID_JSON_BODY` | Ein Payload wird erwartet, der Body ist aber kein gültiges JSON |
| 404 | `NO_MATCHING_ENDPOINT` | Kein Endpunkt für diesen Port und Pfad |
| 405 | `METHOD_NOT_ALLOWED` | Pfad konfiguriert, Methode nicht (Antwort enthält `Allow`) |

## Häufige Fehler beim Start

Eine ungültige Konfiguration bricht den Start ab (Exit-Code 1) und listet **alle** Fehler auf
einmal auf — nicht nur den ersten:

```
Start abgebrochen: Ungueltige Endpunkt-Konfiguration (/pfad/zur/datei.json):
  - endpoints[0] (a): Port 70000 liegt nicht zwischen 1 und 65535
  - endpoints[0] (a): unbekannte HTTP-Methode "FETCH", erlaubt sind [DELETE, OPTIONS, PUT, POST, HEAD, TRACE, GET, PATCH]
  - endpoints[0] (a): Pfad "api/x" muss mit "/" beginnen
  - endpoints[0] (a): Feld "response" fehlt
  - endpoints[2] (c): doppelte Definition von GET /y auf Port 8080 (bereits definiert durch "b")
```

Weitere Meldungen:

| Meldung | Ursache |
| --- | --- |
| `Konfiguration enthaelt keine Endpunkte` | `endpoints` fehlt oder ist leer |
| `Konfiguration ist kein gueltiges JSON` | Syntaxfehler, z. B. ein Komma zu viel |
| `Unrecognized field "respones"` | Tippfehler im Feldnamen — unbekannte Felder werden bewusst abgelehnt |
| `Konfigurationsdatei nicht gefunden` | Der Pfad aus `--config` existiert nicht |
| `doppelte Definition von …` | Zwei Endpunkte mit gleichem Port, gleicher Methode und gleichem Pfad (`/x` und `/x/` gelten als gleich) |

## Wo die Datei gesucht wird

Der erste Treffer gewinnt:

1. `--config=<pfad>` als Kommandozeilenargument
2. System-Property `-Dmockserver.config=<pfad>`
3. Umgebungsvariable `MOCKSERVER_CONFIG`
4. `files/endpoints.json` relativ zum Arbeitsverzeichnis
5. `endpoints.json` im Classpath
