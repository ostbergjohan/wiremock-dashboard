# wiremock-dashboard

WireMock with a visual dashboard for managing stubs, request analytics, and Prometheus metrics.

## Screenshots

![Dashboard](screenshot/wiremock-server.png)

## Quick start

**Docker**
```bash
docker run -p 8080:8080 ostberg/wiremock-dashboard
```

**Local**
```bash
mvn clean install
java -jar target/wiremock-dashboard-0.0.1-jar-with-dependencies.jar
```

Dashboard available at `http://localhost:8080/__admin/dashboard`

## Features

- Visual stub manager — create, edit, delete, import/export
- Request hit counters per stub and URL
- Response time tracking (min / avg / max)
- Unmatched request log with one-click stub creation
- Prometheus metrics at `/metrics`
- JVM metrics at `/__admin/server-metrics`

## Admin API

| Method | Endpoint | Description |
|--------|----------|-------------|
| `GET` | `/__admin/dashboard` | Dashboard UI |
| `GET/POST/DELETE` | `/__admin/mappings` | Manage stubs |
| `GET` | `/__admin/stub-counter` | Hit counts per stub |
| `GET` | `/__admin/response-times` | Response time stats |
| `POST` | `/__admin/reset-stub-counter` | Reset counters |
| `GET` | `/__admin/unmatched-requests` | Unmatched request log |
| `GET` | `/metrics` | Prometheus metrics |

## Tech

Java 21 · WireMock 3.13.0 · Maven

---

[GitHub](https://github.com/ostbergjohan)
