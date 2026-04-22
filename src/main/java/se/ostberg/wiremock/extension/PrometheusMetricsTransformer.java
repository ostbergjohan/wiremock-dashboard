package se.ostberg.wiremock.extension;

import com.github.tomakehurst.wiremock.common.FileSource;
import com.github.tomakehurst.wiremock.extension.Parameters;
import com.github.tomakehurst.wiremock.extension.ResponseDefinitionTransformer;
import com.github.tomakehurst.wiremock.http.Request;
import com.github.tomakehurst.wiremock.http.ResponseDefinition;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;

/**
 * WireMock ResponseDefinitionTransformer that serves Prometheus metrics at GET /metrics.
 * Register as an extension and add a stub for GET /metrics to activate.
 */
public class PrometheusMetricsTransformer extends ResponseDefinitionTransformer {

    private static final PrometheusMetricsExtension SHARED_INSTANCE = new PrometheusMetricsExtension();

    private final PrometheusMetricsExtension metricsExtension;

    public PrometheusMetricsTransformer() {
        this.metricsExtension = SHARED_INSTANCE;
    }

    public PrometheusMetricsTransformer(PrometheusMetricsExtension metricsExtension) {
        this.metricsExtension = metricsExtension;
    }

    @Override
    public String getName() {
        return "prometheus-metrics-transformer";
    }

    @Override
    public boolean applyGlobally() {
        return false;
    }

    @Override
    public ResponseDefinition transform(Request request, ResponseDefinition responseDefinition,
                                        FileSource files, Parameters parameters) {
        return new ResponseDefinitionBuilder()
            .withStatus(200)
            .withBody(metricsExtension.buildMetricsText())
            .withHeader("Content-Type", "text/plain; version=0.0.4; charset=utf-8")
            .build();
    }
}
