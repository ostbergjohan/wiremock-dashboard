package se.ostberg.wiremock.extension;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;

/**
 * Starts a dedicated HTTP server that exposes Prometheus metrics on /metrics.
 * This server runs independently of the WireMock admin API on its own port.
 */
public class PrometheusHttpServer {

    private final HttpServer server;

    public PrometheusHttpServer(int port, PrometheusMetricsExtension metricsExtension) throws IOException {
        server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/metrics", exchange -> {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                exchange.close();
                return;
            }
            byte[] body = metricsExtension.buildMetricsText().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/plain; version=0.0.4; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.setExecutor(java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "prometheus-http-server");
            t.setDaemon(true);
            return t;
        }));
    }

    public void start() {
        server.start();
    }

    public void stop() {
        server.stop(0);
    }
}
