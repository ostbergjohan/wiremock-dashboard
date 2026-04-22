package se.ostberg.wiremock.extension;

import com.github.tomakehurst.wiremock.admin.Router;
import com.github.tomakehurst.wiremock.core.Admin;
import com.github.tomakehurst.wiremock.extension.AdminApiExtension;
import com.github.tomakehurst.wiremock.extension.PostServeAction;
import com.github.tomakehurst.wiremock.http.HttpHeader;
import com.github.tomakehurst.wiremock.http.RequestMethod;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.http.ResponseDefinition;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import com.google.gson.Gson;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tracks unmatched requests (no stub found) in a bounded in-memory ring buffer.
 * No journal required — zero impact on matched-request performance.
 *
 * Exposes:
 *   GET  /__admin/unmatched-requests   -> JSON list (newest first, max 200)
 *   DELETE /__admin/unmatched-requests -> clears the list
 */
public class UnmatchedRequestTracker extends PostServeAction implements AdminApiExtension {

    private static final int MAX_ENTRIES = 200;
    private static final Deque<Map<String, Object>> RING = new ArrayDeque<>(MAX_ENTRIES + 1);
    private static final AtomicLong TOTAL_UNMATCHED = new AtomicLong(0);
    private static final Gson GSON = new Gson();

    // -------------------------------------------------------------------------
    // PostServeAction — fires after every response is sent
    // -------------------------------------------------------------------------

    @Override
    public String getName() {
        return "wiremock-unmatched-tracker";
    }

    @Override
    public void doGlobalAction(ServeEvent serveEvent, Admin admin) {
        if (serveEvent.getWasMatched()) {
            return; // matched — nothing to do
        }

        String url = serveEvent.getRequest().getUrl();

        // Skip browser-automatic requests that are noise
        if (url.equals("/favicon.ico") || url.equals("/favicon.svg") || url.equals("/robots.txt")) {
            return;
        }

        TOTAL_UNMATCHED.incrementAndGet();

        String method = serveEvent.getRequest().getMethod().toString();
        String body   = serveEvent.getRequest().getBodyAsString();
        if (body != null && body.length() > 2000) {
            body = body.substring(0, 2000) + "...";
        }

        // Capture headers
        Map<String, String> headers = new LinkedHashMap<>();
        try {
            for (HttpHeader header : serveEvent.getRequest().getHeaders().all()) {
                headers.put(header.key(), header.firstValue());
            }
        } catch (Exception ignored) {}

        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("timestamp", Instant.now().toString());
        entry.put("method",    method);
        entry.put("url",       url);
        entry.put("body",      body != null ? body : "");
        entry.put("headers",   headers);

        synchronized (RING) {
            if (RING.size() >= MAX_ENTRIES) {
                RING.pollFirst();
            }
            RING.addLast(entry);
        }
    }

    // -------------------------------------------------------------------------
    // AdminApiExtension — exposes /__admin/unmatched-requests
    // -------------------------------------------------------------------------

    @Override
    public void contributeAdminApiRoutes(Router router) {

        // GET /__admin/unmatched-requests
        router.add(RequestMethod.GET, "/unmatched-requests", (admin, serveEvent, pathParams) -> {
            List<Map<String, Object>> copy;
            synchronized (RING) {
                copy = new ArrayList<>(RING);
            }
            Collections.reverse(copy); // newest first
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("total", TOTAL_UNMATCHED.get());
            response.put("stored", copy.size());
            response.put("requests", copy);
            return new ResponseDefinitionBuilder()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(GSON.toJson(response))
                    .build();
        });

        // DELETE /__admin/unmatched-requests
        router.add(RequestMethod.DELETE, "/unmatched-requests", (admin, serveEvent, pathParams) -> {
            synchronized (RING) {
                RING.clear();
            }
            TOTAL_UNMATCHED.set(0);
            return new ResponseDefinitionBuilder().withStatus(204).build();
        });
    }

    // -------------------------------------------------------------------------
    // Static accessor (used by Prometheus metrics if needed)
    // -------------------------------------------------------------------------

    public static long getTotalUnmatched() {
        return TOTAL_UNMATCHED.get();
    }
}
