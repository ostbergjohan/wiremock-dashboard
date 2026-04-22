package se.ostberg.wiremock;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import se.ostberg.wiremock.extension.ExtensionBundle;

public class WireMockStarter {
    public static void main(String[] args) throws Exception {
        int wiremockPort = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));

        WireMockConfiguration config = WireMockConfiguration.options()
            .port(wiremockPort)
            .usingFilesUnderDirectory("wiremock")
            .disableRequestJournal()
            .extensions(ExtensionBundle.create().getExtensions());
        
        WireMockServer wireMockServer = new WireMockServer(config);
        wireMockServer.start();

        System.out.println("WireMock server started on port: " + wireMockServer.port());
        System.out.println("Admin API available at: http://localhost:" + wireMockServer.port() + "/__admin");
        System.out.println("Dashboard available at: http://localhost:" + wireMockServer.port() + "/__admin/dashboard");
        System.out.println("Stub counter available at: http://localhost:" + wireMockServer.port() + "/__admin/stub-counter");
        System.out.println("Reset counter available at: http://localhost:" + wireMockServer.port() + "/__admin/reset-stub-counter");
        System.out.println("Response times at: http://localhost:" + wireMockServer.port() + "/__admin/response-times");
        System.out.println("Server metrics at: http://localhost:" + wireMockServer.port() + "/__admin/server-metrics");
        System.out.println("Record requests at: http://localhost:" + wireMockServer.port() + "/__admin/stub-counter/record");
        System.out.println("Prometheus metrics at: http://localhost:" + wireMockServer.port() + "/metrics");
        System.out.println("Prometheus metrics (admin) at: http://localhost:" + wireMockServer.port() + "/__admin/metrics");
        
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Shutting down WireMock server...");
            wireMockServer.stop();
        }));
    }
}