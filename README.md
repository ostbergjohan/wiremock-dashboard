# wiremock-dashboard

A production-ready WireMock server with a custom dark-themed dashboard and built-in observability.

## Features

- **Dashboard UI** — Create, edit and delete stubs visually at `/__admin/dashboard`
- **Request analytics** — Per-stub and per-URL hit counters at `/__admin/stub-counter`
- **Response time tracking** — Min/max/avg per stub pattern at `/__admin/response-times`
- **Prometheus metrics** — Full JVM + cgroup + stub metrics at `/metrics` and `/__admin/metrics`
- **Unmatched request log** — Last 200 unmatched requests at `/__admin/unmatched-requests`
- **Server metrics** — JVM memory, CPU, threads, GC at `/__admin/server-metrics`

## Quick start

docker run -p 8080:8080 ostberg/wiremock-dashboard

## With persistent mappings

docker run -p 8080:8080 -v $(pwd)/mappings:/wiremock/mappings ostberg/wiremock-dashboard

## Endpoints

| URL | Description |
|-----|-------------|
| `/__admin/dashboard` | Visual stub manager |
| `/__admin/mappings` | REST API for stubs |
| `/metrics` | Prometheus exposition |
| `/__admin/stub-counter` | Request hit counts |
| `/__admin/unmatched-requests` | Unmatched request log |

## Environment variables

| Variable | Default | Description |
|----------|---------|-------------|
| `PORT` | `8080` | HTTP port |
