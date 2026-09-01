# Beispiel-Konfigurationen

Jede Datei ist für sich lauffähig und zeigt einen Aspekt der Konfiguration. Starten mit:

```bash
mvn clean package
java -jar target/chrystalis-mockserver.jar --config=files/examples/01-minimal.json
```

Die vollständige Feldreferenz steht in [`docs/konfiguration.md`](../../docs/konfiguration.md),
das Schema für die IDE in [`files/endpoints.schema.json`](../endpoints.schema.json).

---

## 01-minimal.json — das kleinstmögliche Beispiel

Nur die vier Pflichtangaben `port`, `method`, `path` und `response`. Status wird `200`,
Content-Type wird `application/json` — beides Default.

```bash
curl -i localhost:8080/hello        # 200 {"message":"Hallo Welt"}
curl -i localhost:8080/unbekannt    # 404 NO_MATCHING_ENDPOINT
```

## 02-payload-check.json — erwarteter Payload

Fünf Varianten von `expectedPayload`. Geprüft wird als **Subset**: alles Konfigurierte muss
vorkommen, zusätzliche Felder im Request sind erlaubt.

```bash
# 200 – flaches Objekt, "password" ist ein erlaubtes Zusatzfeld
curl -i -X POST localhost:8080/login \
  -H 'Content-Type: application/json' -d '{"username":"ada","password":"egal"}'

# 201 – verschachteltes Objekt, Feld "zip" zusätzlich erlaubt
curl -i -X POST localhost:8080/api/users \
  -H 'Content-Type: application/json' \
  -d '{"user":{"name":"Ada","address":{"city":"London","zip":"E1"}}}'

# 400 – "city" weicht ab, die Antwort nennt $.user.address.city
curl -i -X POST localhost:8080/api/users \
  -H 'Content-Type: application/json' \
  -d '{"user":{"name":"Ada","address":{"city":"Berlin"}}}'

# 202 – Array: gleiche Länge, elementweise passend, "qty" zusätzlich erlaubt
curl -i -X POST localhost:8080/api/orders \
  -H 'Content-Type: application/json' \
  -d '{"orderId":1001,"items":[{"sku":"ABC-1","qty":2},{"sku":"ABC-2"}]}'

# 400 – Array zu kurz, die Antwort nennt $.items
curl -i -X POST localhost:8080/api/orders \
  -H 'Content-Type: application/json' -d '{"orderId":1001,"items":[{"sku":"ABC-1"}]}'

# 200 – Zahl 3 passt auch als 3.0, "deletedAt" muss vorhanden und null sein
curl -i -X POST localhost:8080/api/flags \
  -H 'Content-Type: application/json' -d '{"retries":3.0,"enabled":true,"deletedAt":null}'

# 200 – leeres erwartetes Objekt {} passt auf jeden Objekt-Body
curl -i -X POST localhost:8080/api/anything \
  -H 'Content-Type: application/json' -d '{"was":"auch immer"}'
```

## 03-multi-port.json — mehrere Ports gleichzeitig

Derselbe Pfad `/api/config` auf drei Ports mit drei verschiedenen Antworten. Der kleinste Port
(8080) wird der Hauptport, 8081 und 9090 bekommen zusätzliche Connectors.

```bash
curl -s localhost:8080/api/config   # {"stage":"prod",...}
curl -s localhost:8081/api/config   # {"stage":"staging",...}
curl -s localhost:9090/api/config   # stage=legacy (text/plain)

curl -i localhost:9090/ping         # 200 pong
curl -i localhost:8080/ping         # 404 – nur auf 9090 definiert
```

## 04-path-patterns.json — Pfade und Muster

Exakte Pfade schlagen Muster; `*` deckt ein Segment ab, `**` mehrere, `{id}` verhält sich wie `*`.

```bash
curl -s localhost:8080/api/users/me      # "ich selbst"  – exakter Pfad gewinnt
curl -s localhost:8080/api/users/42      # "irgendein Benutzer" – Stern-Muster
curl -i -X DELETE localhost:8080/api/users/42   # 204 – {id}-Platzhalter
curl -s localhost:8080/static/js/app.js  # ** deckt mehrere Segmente ab
curl -i localhost:8080/api/users/me/     # 200 – abschließender / wird ignoriert

# 405 – der Pfad ist nur als GET konfiguriert, die Antwort enthält Allow: GET
curl -i -X POST localhost:8080/api/reports
```

## 05-status-and-headers.json — Status, Header und Body-Formen

```bash
curl -i -X POST localhost:8080/api/tickets     # 201 + Location + X-Request-Id
curl -i -X DELETE localhost:8080/api/tickets/7 # 204, leerer Body
curl -i localhost:8080/api/tickets/999         # 404 – bewusst konfigurierter Fehlerfall
curl -i localhost:8080/api/flaky               # 500 – für Fehlerbehandlung im Client
curl -s localhost:8080/api/tickets             # Array als Body
curl -s localhost:8080/api/tickets/count       # 2 – nackte Zahl als Body
curl -i localhost:8080/health                  # text/plain "OK"
curl -i localhost:8080/legacy/feed             # application/xml
```

## 06-rest-api.json — realistische kleine API

Eine Bücher-API auf Port 8080 plus zwei Betriebsendpunkte auf 9090. Diese Datei wird von
`ExampleServerIntegrationTest` automatisch durchgespielt.

```bash
curl -i -X POST localhost:8080/api/v1/auth/token \
  -H 'Content-Type: application/json' -d '{"clientId":"demo","clientSecret":"geheim"}'

curl -s localhost:8080/api/v1/books        # Liste
curl -s localhost:8080/api/v1/books/1      # ein Buch (über {id})
curl -i localhost:8080/api/v1/books/999    # 404 – exakter Pfad schlägt {id}

curl -i -X POST localhost:8080/api/v1/books \
  -H 'Content-Type: application/json' -d '{"title":"Das Schloss","author":"Kafka"}'

curl -i -X PUT localhost:8080/api/v1/books/1 \
  -H 'Content-Type: application/json' -d '{"id":1,"title":"Der Process","author":"Kafka"}'

curl -i -X DELETE localhost:8080/api/v1/books/2   # 204

curl -s localhost:9090/actuator/health     # {"status":"UP"}
curl -s localhost:9090/actuator/info       # Klartext
```
