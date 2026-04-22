# wiremock-dashboard

WireMock mock server with a visual dashboard for creating and managing stubs, built-in request analytics, and Prometheus metrics. Unmatched requests are tracked so you can see what's missing and create stubs directly from the dashboard. Request journaling is disabled by default for low-overhead performance testing.

## Screenshots

![Landing page](screenshot/wiremock-server.png)

![Dashboard — stub manager](screenshot/dashboard.png)

![Stub list with hit counters](screenshot/mocks.png)

## Features

| Feature | Endpoint |
|---------|----------|
| Visual stub manager | `/__admin/dashboard` |
| Request hit counters | `/__admin/stub-counter` |
| Response time stats | `/__admin/response-times` |
| Prometheus metrics | `/metrics` · `/__admin/metrics` |
| Unmatched request log | `/__admin/unmatched-requests` |
| JVM / server metrics | `/__admin/server-metrics` |
| REST stub management | `/__admin/mappings` |

## Quick start

### Docker

```bash
docker run -p 8080:8080 ostberg/wiremock-dashboard
```

### With persistent mappings

```bash
docker run -p 8080:8080 \
  -v $(pwd)/mappings:/app/wiremock/mappings \
  ostberg/wiremock-dashboard
```

### Build and run locally

```bash
mvn clean install
java -jar target/wiremock-dashboard-0.0.1-jar-with-dependencies.jar
```

Server starts on port `8080` by default.

## Environment variables

| Variable | Default | Description |
|----------|---------|-------------|
| `PORT` | `8080` | HTTP listen port |

## Stub management

### Create a stub

```bash
curl -X POST http://localhost:8080/__admin/mappings \
  -H "Content-Type: application/json" \
  -d '{
    "request":  { "method": "GET", "url": "/api/example" },
    "response": { "status": 200, "jsonBody": { "message": "ok" } }
  }'
```

### Import multiple stubs at once

```bash
curl -X POST http://localhost:8080/__admin/mappings/import \
  -H "Content-Type: application/json" \
  -d '{
    "mappings": [ ... ],
    "importOptions": { "duplicatePolicy": "OVERWRITE", "deleteAllNotInImport": true }
  }'
```

### Delete all stubs

```bash
curl -X DELETE http://localhost:8080/__admin/mappings
```

## Admin API reference

| Method | Endpoint | Description |
|--------|----------|-------------|
| `GET` | `/__admin/dashboard` | Visual stub manager UI |
| `GET` | `/__admin/mappings` | List all stubs |
| `POST` | `/__admin/mappings` | Create stub |
| `POST` | `/__admin/mappings/import` | Bulk import stubs |
| `DELETE` | `/__admin/mappings` | Delete all stubs |
| `GET` | `/__admin/stub-counter` | Hit counts per stub pattern |
| `GET` | `/__admin/stub-counter/urls` | Hit counts per exact URL |
| `GET` | `/__admin/stub-counter/summary` | Text summary with percentages |
| `GET` | `/__admin/response-times` | Min/max/avg response times |
| `POST` | `/__admin/reset-stub-counter` | Reset counters and timings |
| `GET` | `/__admin/server-metrics` | JVM memory, CPU, threads, GC |
| `GET` | `/__admin/metrics` | Prometheus exposition format |
| `GET` | `/metrics` | Prometheus (stub path) |
| `GET` | `/__admin/unmatched-requests` | Last 200 unmatched requests |
| `DELETE` | `/__admin/unmatched-requests` | Clear unmatched log |

## Project structure

```
src/main/java/se/ostberg/wiremock/
├── WireMockStarter.java              # Entry point
└── extension/
    ├── ExtensionBundle.java          # Wires all extensions together
    ├── DashboardExtension.java       # Dashboard + favicon routes
    ├── RootRedirectFilter.java       # GET / landing page
    ├── StubCounterExtension.java     # Per-stub hit counting
    ├── StubCounterAdminTask.java     # /__admin/stub-counter routes
    ├── ResetStubCounterAdminTask.java
    ├── ResponseTimeTracker.java      # Min/max/avg timing per pattern
    ├── ServerMetricsExtension.java   # JVM metrics route
    ├── PrometheusMetricsExtension.java  # Prometheus exposition
    ├── PrometheusMetricsTransformer.java
    └── UnmatchedRequestTracker.java  # Unmatched request log

src/main/resources/
├── dashboard/
│   ├── index.html                   # Dashboard UI (dark theme)
│   └── favicon.svg                  # WireMock W-logo

wiremock/
└── mappings/                        # File-based stub definitions
```

## Tech stack

- **Java 21** · **Maven**
- **WireMock 3.13.0** standalone
- **Gson** for JSON serialization

---

## Author

Built by [Johan Ostberg](https://github.com/ostbergjohan)
