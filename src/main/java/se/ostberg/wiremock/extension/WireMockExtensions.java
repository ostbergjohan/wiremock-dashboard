package se.ostberg.wiremock.extension;

import com.github.tomakehurst.wiremock.admin.Router;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.common.Json;
import com.github.tomakehurst.wiremock.common.Timing;
import com.github.tomakehurst.wiremock.core.Admin;
import com.github.tomakehurst.wiremock.extension.AdminApiExtension;
import com.github.tomakehurst.wiremock.extension.PostServeAction;
import com.github.tomakehurst.wiremock.extension.ResponseTransformerV2;
import com.github.tomakehurst.wiremock.extension.requestfilter.RequestFilterAction;
import com.github.tomakehurst.wiremock.extension.requestfilter.StubRequestFilterV2;
import com.github.tomakehurst.wiremock.http.*;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import com.google.gson.Gson;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.management.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Single combined WireMock extension that consolidates all functionality:
 * - Request filter (GET /, GET /favicon.ico, GET /metrics)
 * - Stub counter (ResponseTransformerV2, applied globally)
 * - Response time + unmatched request tracking (PostServeAction)
 * - Admin API routes: dashboard, stub-counter, server-metrics, prometheus, unmatched
 */
public class WireMockExtensions extends PostServeAction
        implements AdminApiExtension, ResponseTransformerV2, StubRequestFilterV2 {

    // =========================================================================
    // State — stub counters
    // =========================================================================

    private final ConcurrentHashMap<String, AtomicLong> stubCounters   = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> urlCounters    = new ConcurrentHashMap<>();
    private final AtomicBoolean counterInitialized                     = new AtomicBoolean(false);

    // =========================================================================
    // State — response-time tracking
    // =========================================================================

    private final ConcurrentHashMap<String, TimingStats> patternTimings = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, TimingStats> urlTimings     = new ConcurrentHashMap<>();

    // =========================================================================
    // State — unmatched requests
    // =========================================================================

    private static final int MAX_UNMATCHED = 200;
    private final Deque<Map<String, Object>> unmatchedRing = new ArrayDeque<>(MAX_UNMATCHED + 1);
    private final AtomicLong totalUnmatched                = new AtomicLong(0);
    private final Gson gson                                = new Gson();

    // =========================================================================
    // State — Prometheus CPU delta tracking
    // =========================================================================

    private final AtomicLong lastCpuTimeNs   = new AtomicLong(-1);
    private final AtomicLong lastCpuSampleMs = new AtomicLong(-1);

    // =========================================================================
    // Extension name
    // =========================================================================

    @Override
    public String getName() {
        return "wiremock-extensions";
    }

    // =========================================================================
    // StubRequestFilterV2 — intercept GET /, GET /favicon.ico, GET /metrics
    // =========================================================================

    @Override
    public RequestFilterAction filter(Request request, ServeEvent serveEvent) {
        String url    = request.getUrl();
        String method = request.getMethod().toString();

        if (!"GET".equals(method)) {
            return RequestFilterAction.continueWith(request);
        }

        return switch (url) {
            case "/" -> RequestFilterAction.stopWith(
                new ResponseDefinitionBuilder()
                    .withStatus(302)
                    .withHeader("Location", "/__admin/dashboard")
                    .withHeader("Cache-Control", "no-cache")
                    .build()
            );
            case "/favicon.ico" -> RequestFilterAction.stopWith(
                new ResponseDefinitionBuilder()
                    .withStatus(204)
                    .withHeader("Cache-Control", "max-age=86400")
                    .build()
            );
            case "/metrics" -> RequestFilterAction.stopWith(
                new ResponseDefinitionBuilder()
                    .withStatus(200)
                    .withHeader("Content-Type", "text/plain; version=0.0.4; charset=utf-8")
                    .withBody(buildMetricsText())
                    .build()
            );
            default -> RequestFilterAction.continueWith(request);
        };
    }

    // =========================================================================
    // ResponseTransformerV2 — global stub hit counting
    // =========================================================================

    @Override
    public Response transform(Response response, ServeEvent serveEvent) {
        if (counterInitialized.compareAndSet(false, true)) {
            System.out.println("========================================");
            System.out.println("WIREMOCK EXTENSIONS ACTIVATED");
            System.out.println("Tracking mock patterns and exact URLs");
            System.out.println("========================================");
        }

        String method = serveEvent.getRequest().getMethod().toString();
        String url    = serveEvent.getRequest().getUrl();

        if ("/".equals(url)) return response;

        String urlKey      = method + ":" + url;
        String stubPattern = getStubPattern(method, url);

        urlCounters.computeIfAbsent(urlKey,      k -> new AtomicLong(0)).incrementAndGet();
        stubCounters.computeIfAbsent(stubPattern, k -> new AtomicLong(0)).incrementAndGet();

        return response;
    }

    @Override
    public boolean applyGlobally() {
        return true;
    }

    // =========================================================================
    // PostServeAction — response time tracking + unmatched request logging
    // =========================================================================

    @Override
    public void doGlobalAction(ServeEvent serveEvent, Admin admin) {
        trackResponseTime(serveEvent);
        trackUnmatched(serveEvent);
    }

    private void trackResponseTime(ServeEvent serveEvent) {
        Timing timing = serveEvent.getTiming();
        if (timing == null) return;
        Integer totalTime = timing.getTotalTime();
        if (totalTime == null) return;

        String method = serveEvent.getRequest().getMethod().toString();
        String url    = serveEvent.getRequest().getUrl();
        if ("/".equals(url)) return;

        long ms        = totalTime.longValue();
        String urlKey  = method + ":" + url;
        String pattern = getStubPattern(method, url);

        urlTimings.computeIfAbsent(urlKey,   k -> new TimingStats()).record(ms);
        patternTimings.computeIfAbsent(pattern, k -> new TimingStats()).record(ms);
    }

    private void trackUnmatched(ServeEvent serveEvent) {
        if (serveEvent.getWasMatched()) return;

        String url = serveEvent.getRequest().getUrl();
        if ("/favicon.ico".equals(url) || "/favicon.svg".equals(url) || "/robots.txt".equals(url)) return;

        totalUnmatched.incrementAndGet();

        String method = serveEvent.getRequest().getMethod().toString();
        String body   = serveEvent.getRequest().getBodyAsString();
        if (body != null && body.length() > 2000) body = body.substring(0, 2000) + "...";

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

        synchronized (unmatchedRing) {
            if (unmatchedRing.size() >= MAX_UNMATCHED) unmatchedRing.pollFirst();
            unmatchedRing.addLast(entry);
        }
    }

    // =========================================================================
    // AdminApiExtension — all admin routes
    // =========================================================================

    @Override
    public void contributeAdminApiRoutes(Router router) {
        registerDashboardRoutes(router);
        registerStubCounterRoutes(router);
        registerServerMetricsRoute(router);
        registerPrometheusRoutes(router);
        registerUnmatchedRoutes(router);
    }

    private void registerDashboardRoutes(Router router) {
        router.add(RequestMethod.GET, "/dashboard", (admin, serveEvent, pathParams) -> {
            try {
                InputStream is = getClass().getResourceAsStream("/dashboard/index.html");
                if (is == null) return new ResponseDefinition(404, "Dashboard not found");
                String html = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))
                    .lines().collect(Collectors.joining("\n"));
                return new ResponseDefinition(200, html);
            } catch (Exception e) {
                return new ResponseDefinition(500, "Error loading dashboard: " + e.getMessage());
            }
        });

        router.add(RequestMethod.GET, "/favicon.svg", (admin, serveEvent, pathParams) -> {
            try {
                InputStream is = getClass().getResourceAsStream("/dashboard/favicon.svg");
                if (is == null) return new ResponseDefinition(404, "Favicon not found");
                String svg = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))
                    .lines().collect(Collectors.joining("\n"));
                return new ResponseDefinitionBuilder()
                    .withStatus(200)
                    .withHeader("Content-Type", "image/svg+xml")
                    .withBody(svg)
                    .build();
            } catch (Exception e) {
                return new ResponseDefinition(500, "Error loading favicon: " + e.getMessage());
            }
        });
    }

    private void registerStubCounterRoutes(Router router) {
        router.add(RequestMethod.GET, "/stub-counter", (admin, serveEvent, pathParams) -> {
            Map<String, Long> counts = getStubCounts();
            Map<String, Long> sortedCounts = sortedByValueDesc(counts);
            long total = counts.values().stream().mapToLong(Long::longValue).sum();
            Map<String, Object> response = new HashMap<>();
            response.put("counts", sortedCounts);
            response.put("total", total);
            return new ResponseDefinition(200, Json.write(response));
        });

        router.add(RequestMethod.GET, "/stub-counter/urls", (admin, serveEvent, pathParams) -> {
            Map<String, Long> counts = getUrlCounts();
            Map<String, Long> sortedCounts = sortedByValueDesc(counts);
            long total = counts.values().stream().mapToLong(Long::longValue).sum();
            Map<String, Object> response = new HashMap<>();
            response.put("counts", sortedCounts);
            response.put("total", total);
            return new ResponseDefinition(200, Json.write(response));
        });

        router.add(RequestMethod.GET, "/stub-counter/summary", (admin, serveEvent, pathParams) -> {
            // Use the same normalized stub pattern counts as Prometheus metrics.
            Map<String, Long> counts = getStubCounts();
            long total = counts.values().stream().mapToLong(Long::longValue).sum();
            if (total == 0) return new ResponseDefinition(200, "No requests recorded yet\n");
            List<Map.Entry<String, Long>> sorted = counts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .collect(Collectors.toList());
            StringBuilder text = new StringBuilder();
            text.append(String.format("Total Requests: %d\n", total));
            text.append(String.format("Total Mocks: %d\n\n", counts.size()));
            text.append(String.format("%-80s %10s %10s\n", "Mock", "Count", "Percent"));
            text.append("=".repeat(102)).append("\n");
            for (Map.Entry<String, Long> e : sorted) {
                double pct = e.getValue() * 100.0 / total;
                text.append(String.format("%-80s %10d %9.2f%%\n", e.getKey(), e.getValue(), pct));
            }
            return new ResponseDefinition(200, text.toString());
        });

        router.add(RequestMethod.GET, "/response-times", (admin, serveEvent, pathParams) -> {
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("patterns", getPatternTimings());
            response.put("urls",     getUrlTimings());
            return new ResponseDefinition(200, Json.write(response));
        });

        router.add(RequestMethod.POST, "/reset-stub-counter", (admin, serveEvent, pathParams) -> {
            resetCounters();
            Map<String, String> response = new HashMap<>();
            response.put("status",  "success");
            response.put("message", "All counters and response times reset");
            return new ResponseDefinition(200, Json.write(response));
        });
    }

    private void registerServerMetricsRoute(Router router) {
        router.add(RequestMethod.GET, "/server-metrics", (admin, serveEvent, pathParams) ->
            new ResponseDefinition(200, Json.write(buildServerMetrics()))
        );
    }

    private void registerPrometheusRoutes(Router router) {
        router.add(RequestMethod.GET, "/prometheus", (admin, serveEvent, pathParams) ->
            new ResponseDefinitionBuilder()
                .withStatus(200)
                .withHeader("Content-Type", "text/plain; version=0.0.4; charset=utf-8")
                .withBody(buildMetricsText())
                .build()
        );
        router.add(RequestMethod.GET, "/metrics", (admin, serveEvent, pathParams) ->
            new ResponseDefinitionBuilder()
                .withStatus(200)
                .withHeader("Content-Type", "text/plain; version=0.0.4; charset=utf-8")
                .withBody(buildMetricsText())
                .build()
        );
    }

    private void registerUnmatchedRoutes(Router router) {
        router.add(RequestMethod.GET, "/unmatched-requests", (admin, serveEvent, pathParams) -> {
            List<Map<String, Object>> copy;
            synchronized (unmatchedRing) {
                copy = new ArrayList<>(unmatchedRing);
            }
            Collections.reverse(copy);
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("total",    totalUnmatched.get());
            response.put("stored",   copy.size());
            response.put("requests", copy);
            return new ResponseDefinitionBuilder()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(gson.toJson(response))
                .build();
        });

        router.add(RequestMethod.DELETE, "/unmatched-requests", (admin, serveEvent, pathParams) -> {
            synchronized (unmatchedRing) {
                unmatchedRing.clear();
            }
            totalUnmatched.set(0);
            return new ResponseDefinitionBuilder().withStatus(204).build();
        });
    }

    // =========================================================================
    // Counter helpers
    // =========================================================================

    private Map<String, Long> getStubCounts() {
        return normalizeStubCounts(stubCounters);
    }

    private Map<String, Long> normalizeStubCounts(ConcurrentHashMap<String, AtomicLong> source) {
        Map<String, Long> result = new HashMap<>();
        source.forEach((key, value) -> {
            String normalizedKey = normalizeStubPatternKey(key);
            result.merge(normalizedKey, value.get(), Long::sum);
        });
        return result;
    }

    private String normalizeStubPatternKey(String key) {
        int colonIndex = key.indexOf(':');
        if (colonIndex <= 0) {
            return key;
        }
        String method = key.substring(0, colonIndex);
        String url = key.substring(colonIndex + 1);
        return getStubPattern(method, url);
    }

    private Map<String, Long> getUrlCounts() {
        Map<String, Long> result = new HashMap<>();
        urlCounters.forEach((k, v) -> result.put(k, v.get()));
        return result;
    }

    private void resetCounters() {
        stubCounters.clear();
        urlCounters.clear();
        patternTimings.clear();
        urlTimings.clear();
        System.out.println("All counters and timing data reset.");
    }

    private Map<String, Map<String, Object>> getPatternTimings() {
        return toTimingMap(patternTimings);
    }

    private Map<String, Map<String, Object>> getUrlTimings() {
        return toTimingMap(urlTimings);
    }

    private Map<String, Map<String, Object>> toTimingMap(ConcurrentHashMap<String, TimingStats> source) {
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        source.entrySet().stream()
            .sorted((a, b) -> Long.compare(b.getValue().count.get(), a.getValue().count.get()))
            .forEach(e -> result.put(e.getKey(), e.getValue().toMap()));
        return result;
    }

    private static Map<String, Long> sortedByValueDesc(Map<String, Long> counts) {
        Map<String, Long> sorted = new LinkedHashMap<>();
        counts.entrySet().stream()
            .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
            .forEach(e -> sorted.put(e.getKey(), e.getValue()));
        return sorted;
    }

    // =========================================================================
    // URL pattern helper
    // =========================================================================

    private static final java.util.regex.Pattern UUID_PATTERN =
        java.util.regex.Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private String normalizePathSegments(String path) {
        String[] segments = path.split("/", -1);
        for (int i = 0; i < segments.length; i++) {
            String seg = segments[i];
            if (UUID_PATTERN.matcher(seg).matches()) {
                segments[i] = "{id}";
            } else if (seg.matches("\\d{5,}")) {
                segments[i] = "{id}";
            }
        }
        return String.join("/", segments);
    }

    private String getStubPattern(String method, String url) {
        String[] parts = url.split("\\?", 2);
        String basePath = normalizePathSegments(parts[0]);
        if (parts.length > 1) {
            String[] params = parts[1].split("&");
            List<String> paramNames = new ArrayList<>();
            for (String param : params) paramNames.add(param.split("=")[0]);
            Collections.sort(paramNames);
            return method + ":" + basePath + "?" + String.join("&", paramNames) + "=*";
        }
        return method + ":" + basePath;
    }

    // =========================================================================
    // TimingStats inner class
    // =========================================================================

    static class TimingStats {
        final AtomicLong min   = new AtomicLong(Long.MAX_VALUE);
        final AtomicLong max   = new AtomicLong(0);
        final AtomicLong sum   = new AtomicLong(0);
        final AtomicLong count = new AtomicLong(0);
        final AtomicLong last  = new AtomicLong(0);

        void record(long micros) {
            count.incrementAndGet();
            sum.addAndGet(micros);
            min.accumulateAndGet(micros, Math::min);
            max.accumulateAndGet(micros, Math::max);
            last.set(micros);
        }

        private static double toMs(long micros) {
            return Math.round(micros / 100.0) / 10.0;
        }

        Map<String, Object> toMap() {
            long c = count.get();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("count", c);
            m.put("last",  toMs(last.get()));
            m.put("min",   min.get() == Long.MAX_VALUE ? 0.0 : toMs(min.get()));
            m.put("max",   toMs(max.get()));
            m.put("avg",   c > 0 ? toMs(sum.get() / c) : 0.0);
            m.put("total", toMs(sum.get()));
            return m;
        }
    }

    // =========================================================================
    // Server metrics (JVM)
    // =========================================================================

    private Map<String, Object> buildServerMetrics() {
        Map<String, Object> metrics = new LinkedHashMap<>();

        MemoryMXBean memBean = ManagementFactory.getMemoryMXBean();
        MemoryUsage heap    = memBean.getHeapMemoryUsage();
        MemoryUsage nonHeap = memBean.getNonHeapMemoryUsage();

        Map<String, Object> memory  = new LinkedHashMap<>();
        Map<String, Object> heapMap = new LinkedHashMap<>();
        heapMap.put("usedMB",      heap.getUsed() / (1024 * 1024));
        heapMap.put("committedMB", heap.getCommitted() / (1024 * 1024));
        heapMap.put("maxMB",       heap.getMax() > 0 ? heap.getMax() / (1024 * 1024) : -1);
        heapMap.put("usagePercent", heap.getMax() > 0
            ? Math.round((double) heap.getUsed() / heap.getMax() * 100) : -1);
        memory.put("heap", heapMap);

        Map<String, Object> nonHeapMap = new LinkedHashMap<>();
        nonHeapMap.put("usedMB",      nonHeap.getUsed() / (1024 * 1024));
        nonHeapMap.put("committedMB", nonHeap.getCommitted() / (1024 * 1024));
        memory.put("nonHeap", nonHeapMap);
        metrics.put("memory", memory);

        OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();
        Map<String, Object> cpu = new LinkedHashMap<>();
        cpu.put("availableProcessors", osBean.getAvailableProcessors());
        cpu.put("systemLoadAverage",   osBean.getSystemLoadAverage());
        if (osBean instanceof com.sun.management.OperatingSystemMXBean sunBean) {
            double processCpu = sunBean.getProcessCpuLoad();
            cpu.put("processCpuPercent", processCpu >= 0 ? Math.round(processCpu * 100) : -1);
            double systemCpu = sunBean.getCpuLoad();
            cpu.put("systemCpuPercent", systemCpu >= 0 ? Math.round(systemCpu * 100) : -1);
        }
        metrics.put("cpu", cpu);

        ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
        Map<String, Object> threads = new LinkedHashMap<>();
        threads.put("current",      threadBean.getThreadCount());
        threads.put("peak",         threadBean.getPeakThreadCount());
        threads.put("daemon",       threadBean.getDaemonThreadCount());
        threads.put("totalStarted", threadBean.getTotalStartedThreadCount());
        metrics.put("threads", threads);

        List<Map<String, Object>> gcList = new ArrayList<>();
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            Map<String, Object> gcMap = new LinkedHashMap<>();
            gcMap.put("name",        gc.getName());
            gcMap.put("collections", gc.getCollectionCount());
            gcMap.put("totalTimeMs", gc.getCollectionTime());
            gcList.add(gcMap);
        }
        metrics.put("gc", gcList);

        RuntimeMXBean rtBean = ManagementFactory.getRuntimeMXBean();
        Map<String, Object> runtime = new LinkedHashMap<>();
        long uptimeMs = rtBean.getUptime();
        runtime.put("uptimeMs",        uptimeMs);
        runtime.put("uptimeFormatted", formatUptime(uptimeMs));
        runtime.put("vmName",          rtBean.getVmName());
        runtime.put("vmVersion",       rtBean.getVmVersion());
        runtime.put("pid",             ProcessHandle.current().pid());
        metrics.put("runtime", runtime);

        return metrics;
    }

    private static String formatUptime(long ms) {
        long seconds = ms / 1000;
        long days    = seconds / 86400;
        long hours   = (seconds % 86400) / 3600;
        long minutes = (seconds % 3600) / 60;
        long secs    = seconds % 60;
        if (days > 0)    return days + "d " + hours + "h " + minutes + "m";
        if (hours > 0)   return hours + "h " + minutes + "m " + secs + "s";
        if (minutes > 0) return minutes + "m " + secs + "s";
        return secs + "s";
    }

    // =========================================================================
    // Prometheus metrics text
    // =========================================================================

    public String buildMetricsText() {
        StringBuilder sb = new StringBuilder();
        appendJvmMemory(sb);
        appendJvmCpu(sb);
        appendJvmThreads(sb);
        appendJvmGc(sb);
        appendJvmUptime(sb);
        appendJvmSettings(sb);
        appendCgroupMemory(sb);
        appendCgroupCpu(sb);
        appendCgroupIo(sb);
        appendProcMetrics(sb);
        appendStubCounters(sb);
        appendResponseTimes(sb);
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // JVM Memory
    // -------------------------------------------------------------------------

    private void appendJvmMemory(StringBuilder sb) {
        MemoryMXBean mem    = ManagementFactory.getMemoryMXBean();
        MemoryUsage heap    = mem.getHeapMemoryUsage();
        MemoryUsage nonHeap = mem.getNonHeapMemoryUsage();

        gauge(sb, "wiremock_jvm_memory_heap_used_bytes",
            "JVM heap memory used in bytes", heap.getUsed());
        gauge(sb, "wiremock_jvm_memory_heap_committed_bytes",
            "JVM heap memory committed in bytes", heap.getCommitted());
        gauge(sb, "wiremock_jvm_memory_heap_max_bytes",
            "JVM heap memory max in bytes (\u22121 if undefined)", heap.getMax());
        gauge(sb, "wiremock_jvm_memory_nonheap_used_bytes",
            "JVM non-heap memory used in bytes", nonHeap.getUsed());
        gauge(sb, "wiremock_jvm_memory_nonheap_committed_bytes",
            "JVM non-heap memory committed in bytes", nonHeap.getCommitted());
    }

    // -------------------------------------------------------------------------
    // JVM CPU
    // -------------------------------------------------------------------------

    private void appendJvmCpu(StringBuilder sb) {
        OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
        gauge(sb, "wiremock_jvm_cpu_available_processors",
            "Number of processors available to the JVM", os.getAvailableProcessors());
        gauge(sb, "wiremock_jvm_cpu_system_load_average",
            "System load average for the last minute (\u22121 if unavailable)", os.getSystemLoadAverage());
        if (os instanceof com.sun.management.OperatingSystemMXBean sun) {
            double procCpu = sun.getProcessCpuLoad();
            double sysCpu  = sun.getCpuLoad();
            gauge(sb, "wiremock_jvm_cpu_process_usage_ratio",
                "JVM process CPU load (0.0\u20131.0, \u22121 if unavailable)", procCpu);
            gauge(sb, "wiremock_jvm_cpu_system_usage_ratio",
                "System-wide CPU load (0.0\u20131.0, \u22121 if unavailable)", sysCpu);
        }
    }

    // -------------------------------------------------------------------------
    // JVM Threads
    // -------------------------------------------------------------------------

    private void appendJvmThreads(StringBuilder sb) {
        ThreadMXBean t = ManagementFactory.getThreadMXBean();
        gauge(sb,   "wiremock_jvm_threads_current",
            "Current live thread count", t.getThreadCount());
        gauge(sb,   "wiremock_jvm_threads_peak",
            "Peak live thread count since JVM start", t.getPeakThreadCount());
        gauge(sb,   "wiremock_jvm_threads_daemon",
            "Current daemon thread count", t.getDaemonThreadCount());
        counter(sb, "wiremock_jvm_threads_started_total",
            "Total threads started since JVM start", t.getTotalStartedThreadCount());
    }

    // -------------------------------------------------------------------------
    // JVM GC
    // -------------------------------------------------------------------------

    private void appendJvmGc(StringBuilder sb) {
        sb.append("# HELP wiremock_jvm_gc_collection_count_total GC collection count per collector.\n");
        sb.append("# TYPE wiremock_jvm_gc_collection_count_total counter\n");
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            sb.append("wiremock_jvm_gc_collection_count_total{gc=\"")
              .append(escapeLabelValue(gc.getName())).append("\"} ")
              .append(gc.getCollectionCount()).append("\n");
        }
        sb.append("\n");

        sb.append("# HELP wiremock_jvm_gc_collection_time_ms_total Accumulated GC pause time in ms per collector.\n");
        sb.append("# TYPE wiremock_jvm_gc_collection_time_ms_total counter\n");
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            sb.append("wiremock_jvm_gc_collection_time_ms_total{gc=\"")
              .append(escapeLabelValue(gc.getName())).append("\"} ")
              .append(gc.getCollectionTime()).append("\n");
        }
        sb.append("\n");
    }

    // -------------------------------------------------------------------------
    // JVM Uptime
    // -------------------------------------------------------------------------

    private void appendJvmUptime(StringBuilder sb) {
        long uptimeMs = ManagementFactory.getRuntimeMXBean().getUptime();
        counter(sb, "wiremock_jvm_uptime_seconds_total",
            "JVM uptime in seconds", uptimeMs / 1000.0);
    }

    // -------------------------------------------------------------------------
    // JVM Settings
    // -------------------------------------------------------------------------

    private void appendJvmSettings(StringBuilder sb) {
        RuntimeMXBean rt  = ManagementFactory.getRuntimeMXBean();
        List<String> args = rt.getInputArguments();

        double maxRamPct         = parseDoubleFlag(args, "MaxRAMPercentage");
        double initRamPct        = parseDoubleFlag(args, "InitialRAMPercentage");
        long   metaspaceMax      = parseSizeFlag(args, "MaxMetaspaceSize");
        long   codeCacheReserved = parseSizeFlag(args, "ReservedCodeCacheSize");
        long   heapMax           = Runtime.getRuntime().maxMemory();
        boolean containerSupport = args.stream().anyMatch(a -> a.contains("+UseContainerSupport"));
        boolean useZgc           = args.stream().anyMatch(a -> a.contains("+UseZGC"));
        boolean zgcGenerational  = args.stream().anyMatch(a -> a.contains("+ZGenerational"));
        boolean exitOnOom        = args.stream().anyMatch(a -> a.contains("+ExitOnOutOfMemoryError"));

        String gcNames = ManagementFactory.getGarbageCollectorMXBeans().stream()
            .map(GarbageCollectorMXBean::getName)
            .reduce((a, b) -> a + ", " + b)
            .orElse("unknown");

        if (maxRamPct >= 0)         gauge(sb, "wiremock_jvm_max_ram_percentage",
            "JVM MaxRAMPercentage setting", maxRamPct);
        if (initRamPct >= 0)        gauge(sb, "wiremock_jvm_initial_ram_percentage",
            "JVM InitialRAMPercentage setting", initRamPct);
        if (metaspaceMax >= 0)      gauge(sb, "wiremock_jvm_metaspace_max_bytes",
            "JVM MaxMetaspaceSize in bytes", metaspaceMax);
        if (codeCacheReserved >= 0) gauge(sb, "wiremock_jvm_code_cache_reserved_bytes",
            "JVM ReservedCodeCacheSize in bytes", codeCacheReserved);
        gauge(sb, "wiremock_jvm_heap_max_bytes",
            "Effective JVM heap max bytes (Runtime.maxMemory)", heapMax);

        sb.append("# HELP wiremock_jvm_settings_info JVM flag configuration.\n");
        sb.append("# TYPE wiremock_jvm_settings_info gauge\n");
        sb.append("wiremock_jvm_settings_info{");
        sb.append("gc=\"").append(escapeLabelValue(gcNames)).append("\",");
        sb.append("container_support=\"").append(containerSupport).append("\",");
        sb.append("use_zgc=\"").append(useZgc).append("\",");
        sb.append("zgc_generational=\"").append(zgcGenerational).append("\",");
        sb.append("exit_on_oom=\"").append(exitOnOom).append("\"");
        sb.append("} 1.0\n\n");
    }

    private double parseDoubleFlag(List<String> args, String flag) {
        for (String arg : args) {
            if (arg.contains(flag + "=")) {
                try { return Double.parseDouble(arg.substring(arg.indexOf('=') + 1)); }
                catch (NumberFormatException ignored) {}
            }
        }
        return -1;
    }

    private long parseSizeFlag(List<String> args, String flag) {
        for (String arg : args) {
            if (arg.contains(flag + "=")) {
                String val = arg.substring(arg.indexOf('=') + 1).trim().toLowerCase();
                try {
                    if (val.endsWith("g")) return Long.parseLong(val.substring(0, val.length()-1)) * 1024 * 1024 * 1024;
                    if (val.endsWith("m")) return Long.parseLong(val.substring(0, val.length()-1)) * 1024 * 1024;
                    if (val.endsWith("k")) return Long.parseLong(val.substring(0, val.length()-1)) * 1024;
                    return Long.parseLong(val);
                } catch (NumberFormatException ignored) {}
            }
        }
        return -1;
    }

    // -------------------------------------------------------------------------
    // cgroup Memory
    // -------------------------------------------------------------------------

    private void appendCgroupMemory(StringBuilder sb) {
        Path usageV2 = Path.of("/sys/fs/cgroup/memory.current");
        Path limitV2  = Path.of("/sys/fs/cgroup/memory.max");
        Path swapV2   = Path.of("/sys/fs/cgroup/memory.swap.current");
        Path usageV1 = Path.of("/sys/fs/cgroup/memory/memory.usage_in_bytes");
        Path limitV1  = Path.of("/sys/fs/cgroup/memory/memory.limit_in_bytes");
        Path swapV1   = Path.of("/sys/fs/cgroup/memory/memory.memsw.usage_in_bytes");

        long usage = readLongFile(Files.exists(usageV2) ? usageV2 : usageV1);
        long limit = readLongFile(Files.exists(limitV2) ? limitV2 : limitV1);
        long swap  = readLongFile(Files.exists(swapV2)  ? swapV2  : swapV1);

        if (usage < 0 && limit < 0) return;

        if (usage >= 0) gauge(sb, "container_memory_usage_bytes",
            "Container memory usage in bytes (cgroup)", usage);
        if (limit >= 0) gauge(sb, "container_memory_limit_bytes",
            "Container memory limit in bytes (cgroup)", limit);
        if (swap >= 0)  gauge(sb, "container_memory_swap_bytes",
            "Container swap usage in bytes (cgroup, -1 if unavailable)", swap);
    }

    // -------------------------------------------------------------------------
    // cgroup CPU
    // -------------------------------------------------------------------------

    private void appendCgroupCpu(StringBuilder sb) {
        long cpuTimeUs   = readCgroupCpuTimeUs();
        long limitCores  = readCgroupCpuLimitCores();

        if (cpuTimeUs < 0 && limitCores < 0) return;

        if (limitCores >= 0) gauge(sb, "container_cpu_limit_cores",
            "CPU cores allocated by cgroup quota (-1 = unlimited)", limitCores);

        long nowMs    = System.currentTimeMillis();
        long lastTime = lastCpuTimeNs.get();
        long lastMs   = lastCpuSampleMs.get();

        if (lastTime >= 0 && lastMs >= 0 && cpuTimeUs >= 0) {
            long deltaUs = cpuTimeUs - lastTime;
            long deltaMs = nowMs - lastMs;
            if (deltaMs > 0 && limitCores > 0) {
                double usagePct = (deltaUs / 1000.0) / (deltaMs * limitCores) * 100.0;
                gauge(sb, "container_cpu_usage_percent",
                    "Container CPU usage percent relative to limit",
                    Math.min(usagePct, 100.0));
            }
        }
        lastCpuTimeNs.set(cpuTimeUs);
        lastCpuSampleMs.set(nowMs);
    }

    private long readCgroupCpuTimeUs() {
        Path v2 = Path.of("/sys/fs/cgroup/cpu.stat");
        if (Files.exists(v2)) {
            try {
                for (String line : Files.readAllLines(v2)) {
                    if (line.startsWith("usage_usec ")) {
                        return Long.parseLong(line.split(" ")[1].trim());
                    }
                }
            } catch (Exception ignored) {}
        }
        Path v1 = Path.of("/sys/fs/cgroup/cpuacct/cpuacct.usage");
        if (Files.exists(v1)) {
            long nanos = readLongFile(v1);
            return nanos >= 0 ? nanos / 1000 : -1;
        }
        return -1;
    }

    private long readCgroupCpuLimitCores() {
        Path v2 = Path.of("/sys/fs/cgroup/cpu.max");
        if (Files.exists(v2)) {
            try {
                String content = Files.readString(v2).trim();
                String[] parts = content.split("\\s+");
                if (parts.length == 2 && !parts[0].equals("max")) {
                    long quota  = Long.parseLong(parts[0]);
                    long period = Long.parseLong(parts[1]);
                    return period > 0 ? quota / period : -1;
                }
                return -1;
            } catch (Exception ignored) {}
        }
        Path quotaV1  = Path.of("/sys/fs/cgroup/cpu/cpu.cfs_quota_us");
        Path periodV1 = Path.of("/sys/fs/cgroup/cpu/cpu.cfs_period_us");
        if (Files.exists(quotaV1) && Files.exists(periodV1)) {
            long quota  = readLongFile(quotaV1);
            long period = readLongFile(periodV1);
            if (quota > 0 && period > 0) return quota / period;
        }
        return -1;
    }

    // -------------------------------------------------------------------------
    // cgroup I/O (v2 only)
    // -------------------------------------------------------------------------

    private void appendCgroupIo(StringBuilder sb) {
        Path ioStat = Path.of("/sys/fs/cgroup/io.stat");
        if (!Files.exists(ioStat)) return;
        try {
            boolean headerRead  = false;
            boolean headerWrite = false;
            for (String line : Files.readAllLines(ioStat)) {
                String[] parts = line.split("\\s+");
                if (parts.length < 2) continue;
                String device = parts[0];
                long rbytes = -1, wbytes = -1;
                for (int i = 1; i < parts.length; i++) {
                    if (parts[i].startsWith("rbytes=")) rbytes = Long.parseLong(parts[i].substring(7));
                    if (parts[i].startsWith("wbytes=")) wbytes = Long.parseLong(parts[i].substring(7));
                }
                if (rbytes >= 0) {
                    if (!headerRead) {
                        sb.append("# HELP container_io_read_bytes_total Cumulative bytes read per block device (cgroup v2).\n");
                        sb.append("# TYPE container_io_read_bytes_total counter\n");
                        headerRead = true;
                    }
                    sb.append("container_io_read_bytes_total{device=\"")
                      .append(escapeLabelValue(device)).append("\"} ").append(rbytes).append("\n");
                }
                if (wbytes >= 0) {
                    if (!headerWrite) {
                        sb.append("\n# HELP container_io_write_bytes_total Cumulative bytes written per block device (cgroup v2).\n");
                        sb.append("# TYPE container_io_write_bytes_total counter\n");
                        headerWrite = true;
                    }
                    sb.append("container_io_write_bytes_total{device=\"")
                      .append(escapeLabelValue(device)).append("\"} ").append(wbytes).append("\n");
                }
            }
            if (headerRead || headerWrite) sb.append("\n");
        } catch (Exception ignored) {}
    }

    // -------------------------------------------------------------------------
    // /proc metrics
    // -------------------------------------------------------------------------

    private void appendProcMetrics(StringBuilder sb) {
        appendProcStatus(sb);
        appendProcSmaps(sb);
        appendProcIo(sb);
        appendProcNet(sb);
    }

    private void appendProcStatus(StringBuilder sb) {
        Path status = Path.of("/proc/self/status");
        if (!Files.exists(status)) return;
        try {
            long rss = -1, vmPeak = -1, threads = -1;
            for (String line : Files.readAllLines(status)) {
                if (line.startsWith("VmRSS:"))   rss     = parseKbLine(line);
                if (line.startsWith("VmPeak:"))  vmPeak  = parseKbLine(line);
                if (line.startsWith("Threads:")) threads = Long.parseLong(line.split(":")[1].trim());
            }
            if (rss >= 0)     gauge(sb, "process_vm_rss_bytes",
                "Resident Set Size \u2014 actual RAM used by process", rss * 1024);
            if (vmPeak >= 0)  gauge(sb, "process_vm_peak_bytes",
                "Peak virtual memory size of the process", vmPeak * 1024);
            if (threads >= 0) gauge(sb, "process_threads_total",
                "Number of active threads in the JVM process", threads);
        } catch (Exception ignored) {}
    }

    private void appendProcSmaps(StringBuilder sb) {
        Path smaps = Path.of("/proc/self/smaps_rollup");
        if (!Files.exists(smaps)) return;
        try {
            for (String line : Files.readAllLines(smaps)) {
                if (line.startsWith("Pss:")) {
                    long pss = parseKbLine(line);
                    if (pss >= 0) gauge(sb, "process_pss_bytes",
                        "Proportional Set Size including shared memory", pss * 1024);
                    break;
                }
            }
        } catch (Exception ignored) {}
    }

    private void appendProcIo(StringBuilder sb) {
        Path io = Path.of("/proc/self/io");
        if (!Files.exists(io)) return;
        try {
            long readBytes = -1, writeBytes = -1;
            for (String line : Files.readAllLines(io)) {
                if (line.startsWith("read_bytes:"))  readBytes  = Long.parseLong(line.split(":")[1].trim());
                if (line.startsWith("write_bytes:")) writeBytes = Long.parseLong(line.split(":")[1].trim());
            }
            if (readBytes >= 0)  counter(sb, "process_io_read_bytes_total",
                "Cumulative bytes read from disk by this process", readBytes);
            if (writeBytes >= 0) counter(sb, "process_io_write_bytes_total",
                "Cumulative bytes written to disk by this process", writeBytes);
        } catch (Exception ignored) {}
    }

    private void appendProcNet(StringBuilder sb) {
        Path netDev = Path.of("/proc/self/net/dev");
        if (!Files.exists(netDev)) return;
        try {
            List<String> lines = Files.readAllLines(netDev);
            List<String[]> ifaces = new ArrayList<>();
            for (int i = 2; i < lines.size(); i++) {
                String line = lines.get(i).trim();
                if (line.isEmpty()) continue;
                String[] parts = line.split("[:\\s]+");
                if (parts.length < 10) continue;
                if (parts[0].equals("lo")) continue;
                ifaces.add(parts);
            }
            if (ifaces.isEmpty()) return;

            sb.append("# HELP process_network_receive_bytes_total Cumulative bytes received per interface.\n");
            sb.append("# TYPE process_network_receive_bytes_total counter\n");
            for (String[] parts : ifaces) {
                sb.append("process_network_receive_bytes_total{interface=\"")
                  .append(escapeLabelValue(parts[0])).append("\"} ")
                  .append(Long.parseLong(parts[1])).append("\n");
            }
            sb.append("\n");

            sb.append("# HELP process_network_transmit_bytes_total Cumulative bytes transmitted per interface.\n");
            sb.append("# TYPE process_network_transmit_bytes_total counter\n");
            for (String[] parts : ifaces) {
                sb.append("process_network_transmit_bytes_total{interface=\"")
                  .append(escapeLabelValue(parts[0])).append("\"} ")
                  .append(Long.parseLong(parts[9])).append("\n");
            }
            sb.append("\n");
        } catch (Exception ignored) {}
    }

    // -------------------------------------------------------------------------
    // /proc helpers
    // -------------------------------------------------------------------------

    private long parseKbLine(String line) {
        try {
            String[] parts = line.split(":")[1].trim().split("\\s+");
            return Long.parseLong(parts[0]);
        } catch (Exception e) {
            return -1;
        }
    }

    private long readLongFile(Path path) {
        try {
            if (!Files.exists(path)) return -1;
            String content = Files.readString(path).trim();
            if (content.equals("max")) return Long.MAX_VALUE;
            return Long.parseLong(content);
        } catch (Exception e) {
            return -1;
        }
    }

    // -------------------------------------------------------------------------
    // Stub counters in Prometheus format
    // -------------------------------------------------------------------------

    private void appendStubCounters(StringBuilder sb) {
        Map<String, Long> counts = getStubCounts();
        if (counts.isEmpty()) return;

        sb.append("# HELP wiremock_stub_requests_total Total number of requests matched per stub pattern.\n");
        sb.append("# TYPE wiremock_stub_requests_total counter\n");
        counts.entrySet().stream()
            .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
            .forEach(e -> sb.append("wiremock_stub_requests_total{stub=\"")
                .append(escapeLabelValue(e.getKey())).append("\"} ")
                .append(e.getValue()).append("\n"));
        sb.append("\n");
    }

    // -------------------------------------------------------------------------
    // Response times in Prometheus format
    // -------------------------------------------------------------------------

    private void appendResponseTimes(StringBuilder sb) {
        Map<String, Map<String, Object>> timings = getPatternTimings();
        if (timings.isEmpty()) return;

        writeLabelledGauge(sb, "wiremock_stub_response_time_min_ms",
            "Minimum response time in ms per stub pattern", timings, "min");
        writeLabelledGauge(sb, "wiremock_stub_response_time_max_ms",
            "Maximum response time in ms per stub pattern", timings, "max");
        writeLabelledGauge(sb, "wiremock_stub_response_time_avg_ms",
            "Average response time in ms per stub pattern", timings, "avg");

        sb.append("# HELP wiremock_stub_response_time_count_total Request count with timing per stub pattern.\n");
        sb.append("# TYPE wiremock_stub_response_time_count_total counter\n");
        timings.forEach((pattern, stats) -> {
            Object raw = stats.get("count");
            if (raw != null) {
                sb.append("wiremock_stub_response_time_count_total{stub=\"")
                  .append(escapeLabelValue(pattern)).append("\"} ")
                  .append(raw).append("\n");
            }
        });
        sb.append("\n");
    }

    private void writeLabelledGauge(StringBuilder sb, String name, String help,
                                    Map<String, Map<String, Object>> timings, String key) {
        sb.append("# HELP ").append(name).append(" ").append(help).append(".\n");
        sb.append("# TYPE ").append(name).append(" gauge\n");
        timings.forEach((pattern, stats) -> {
            Object raw = stats.get(key);
            if (raw != null) {
                sb.append(name).append("{stub=\"")
                  .append(escapeLabelValue(pattern)).append("\"} ")
                  .append(raw).append("\n");
            }
        });
        sb.append("\n");
    }

    // -------------------------------------------------------------------------
    // Prometheus format helpers
    // -------------------------------------------------------------------------

    private void gauge(StringBuilder sb, String name, String help, double value) {
        sb.append("# HELP ").append(name).append(" ").append(help).append(".\n");
        sb.append("# TYPE ").append(name).append(" gauge\n");
        sb.append(name).append(" ").append(value).append("\n\n");
    }

    private void gauge(StringBuilder sb, String name, String help, long value) {
        gauge(sb, name, help, (double) value);
    }

    private void counter(StringBuilder sb, String name, String help, double value) {
        sb.append("# HELP ").append(name).append(" ").append(help).append(".\n");
        sb.append("# TYPE ").append(name).append(" counter\n");
        sb.append(name).append(" ").append(value).append("\n\n");
    }

    private void counter(StringBuilder sb, String name, String help, long value) {
        counter(sb, name, help, (double) value);
    }

    private String escapeLabelValue(String value) {
        return value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n");
    }

    // =========================================================================
    // Landing page HTML
    // =========================================================================

    private static final String INFO_PAGE = """
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<meta name="description" content="WireMock Mock Server - WireMock service virtualization">
<meta name="theme-color" content="#1E1B4B">
<link rel="icon" type="image/svg+xml" href="/__admin/favicon.svg">
<title>WireMock Mock Server</title>
<style>
:root {
    --color-primary: #4F46E5;
    --color-primary-dark: #3730A3;
    --color-accent: #F97316;
    --color-success: #16A34A;
    --color-font-mono: ui-monospace, 'Courier New', Courier, monospace;
    --transition-base: 250ms cubic-bezier(0.4, 0, 0.2, 1);
    --radius-sm: 4px;
    --radius-base: 10px;
    --radius-card: 14px;
}
*, *::before, *::after { box-sizing: border-box; margin: 0; padding: 0; }
*:focus-visible { outline: 2px solid #818cf8; outline-offset: 2px; }
body {
    font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', 'Inter', Roboto, sans-serif;
    margin: 0;
    background: #0F0E1A;
    color: #c7d2fe;
    font-size: 14px;
    line-height: 1.5;
    display: flex;
    flex-direction: column;
    align-items: center;
    min-height: 100vh;
    padding: 0 0 48px 0;
}
.topbar {
    width: 100%;
    background: linear-gradient(135deg, #1E1B4B 0%, #312E81 100%);
    border-bottom: 1px solid rgba(129,140,248,0.2);
    padding: 0 32px;
    height: 60px;
    display: flex;
    align-items: center;
    gap: 14px;
    box-shadow: 0 4px 24px rgba(0,0,0,0.4);
    margin-bottom: 40px;
    position: sticky;
    top: 0;
    z-index: 100;
}
.topbar-logo { width: 34px; height: 34px; flex-shrink: 0; filter: drop-shadow(0 0 8px rgba(249,115,22,0.5)); }
.topbar-title { font-size: 16px; font-weight: 700; color: #fff; letter-spacing: -0.2px; }
.topbar-sub { font-size: 11px; color: rgba(165,180,252,0.7); margin-left: 2px; letter-spacing: 0.2px; }
.main-container { max-width: 860px; width: 100%; padding: 0 24px; }
.hero { text-align: center; margin-bottom: 40px; }
.hero-logo { width: 72px; height: 72px; margin: 0 auto 20px; filter: drop-shadow(0 0 20px rgba(249,115,22,0.4)); }
.hero h1 { font-size: 30px; font-weight: 800; color: #e0e7ff; letter-spacing: -0.5px; margin-bottom: 10px; display: flex; align-items: center; justify-content: center; gap: 10px; }
.hero p { font-size: 15px; color: #64748b; max-width: 520px; margin: 0 auto; line-height: 1.7; }
.info-btn { background: none; border: 1px solid rgba(129,140,248,0.3); padding: 4px 6px; cursor: pointer; color: #818cf8; display: inline-flex; align-items: center; justify-content: center; border-radius: 50%; transition: all 0.15s ease; vertical-align: middle; }
.info-btn:hover { background: rgba(129,140,248,0.1); border-color: rgba(129,140,248,0.6); color: #c7d2fe; }
.section-label { font-size: 11px; font-weight: 700; text-transform: uppercase; letter-spacing: 0.8px; color: #475569; margin-bottom: 12px; margin-top: 32px; }
.section-label:first-child { margin-top: 0; }
.nav-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(175px, 1fr)); gap: 14px; }
.nav-card { background: #1A1830; border: 1px solid rgba(129,140,248,0.15); border-radius: var(--radius-card); padding: 22px 16px; text-decoration: none; text-align: center; display: flex; flex-direction: column; align-items: center; gap: 8px; transition: all var(--transition-base); color: inherit; position: relative; overflow: hidden; }
.nav-card::before { content: ''; position: absolute; top: 0; left: 0; right: 0; height: 2px; background: linear-gradient(90deg, #4F46E5, #818cf8); opacity: 0; transition: opacity var(--transition-base); }
.nav-card:hover { border-color: rgba(129,140,248,0.45); transform: translateY(-3px); box-shadow: 0 12px 28px rgba(0,0,0,0.4); }
.nav-card:hover::before { opacity: 1; }
.nav-card .card-icon { color: #818cf8; transition: color var(--transition-base); }
.nav-card:hover .card-icon { color: #F97316; }
.nav-card h3 { font-size: 14px; font-weight: 600; color: #e0e7ff; margin: 0; }
.nav-card p { font-size: 12px; color: #64748b; margin: 0; line-height: 1.5; }
.info-card { background: #1A1830; border: 1px solid rgba(129,140,248,0.13); border-radius: var(--radius-base); overflow: hidden; margin-bottom: 14px; }
.info-card:last-child { margin-bottom: 0; }
.info-card-h { padding: 12px 20px; border-bottom: 1px solid rgba(129,140,248,0.1); font-weight: 600; font-size: 14px; color: #c7d2fe; background: rgba(79,70,229,0.06); }
.info-card-c { padding: 16px 20px; font-size: 13px; color: #94a3b8; line-height: 1.7; }
.info-card-c p { margin-bottom: 12px; }
.info-card-c p:last-child { margin-bottom: 0; }
.info-card-c ul { list-style: none; padding: 0; margin: 0 0 12px 0; }
.info-card-c ul:last-child { margin-bottom: 0; }
.info-card-c li { padding: 4px 0 4px 16px; position: relative; }
.info-card-c li::before { content: "\\2022"; position: absolute; left: 0; color: #F97316; font-weight: bold; }
.info-card-c a { color: #818cf8; text-decoration: none; font-weight: 500; }
.info-card-c a:hover { color: #c7d2fe; text-decoration: underline; }
.info-card-c code { font-family: var(--color-font-mono); font-size: 12px; background: rgba(79,70,229,0.12); color: #a5b4fc; padding: 2px 7px; border-radius: 4px; border: 1px solid rgba(79,70,229,0.2); }
.table-scroll { overflow-x: auto; }
.api-table { width: 100%; border-collapse: collapse; font-size: 13px; }
.api-table th { text-align: left; font-weight: 700; font-size: 11px; text-transform: uppercase; letter-spacing: 0.5px; color: #475569; padding: 10px 16px; border-bottom: 1px solid rgba(129,140,248,0.12); background: rgba(79,70,229,0.05); }
.api-table td { padding: 10px 16px; border-bottom: 1px solid rgba(129,140,248,0.07); vertical-align: middle; color: #94a3b8; }
.api-table tr:last-child td { border-bottom: none; }
.api-table tbody tr:hover { background: rgba(129,140,248,0.04); }
.method { display: inline-block; font-family: var(--color-font-mono); font-size: 11px; font-weight: 700; padding: 2px 8px; border-radius: var(--radius-sm); min-width: 52px; text-align: center; }
.method.get    { background: rgba(8,145,178,0.15); color: #67e8f9; }
.method.post   { background: rgba(22,163,74,0.13); color: #86efac; }
.method.delete { background: rgba(220,38,38,0.13); color: #fca5a5; }
.path { font-family: var(--color-font-mono); font-size: 12px; color: #6366f1; }
.footer { text-align: center; margin-top: 40px; font-size: 12px; color: #334155; }
.footer a { color: #6366f1; text-decoration: none; }
.footer a:hover { color: #a5b4fc; }
.modal-overlay { position: fixed; inset: 0; background: rgba(0,0,0,0.65); display: flex; align-items: center; justify-content: center; z-index: 1000; backdrop-filter: blur(4px); }
.modal-overlay.hidden { display: none; }
.modal-content { background: #1A1830; border: 1px solid rgba(129,140,248,0.2); max-width: 580px; width: 90%; max-height: 80vh; overflow-y: auto; padding: 28px; border-radius: 14px; box-shadow: 0 25px 50px rgba(0,0,0,0.7); }
.modal-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 18px; padding-bottom: 14px; border-bottom: 1px solid rgba(129,140,248,0.12); }
.modal-header h2 { margin: 0; color: #e0e7ff; font-size: 17px; font-weight: 700; }
.modal-close { background: none; border: 1px solid rgba(129,140,248,0.2); font-size: 1.1rem; cursor: pointer; color: #64748b; padding: 4px 8px; border-radius: 6px; transition: all 0.15s ease; }
.modal-close:hover { color: #e0e7ff; background: rgba(129,140,248,0.1); border-color: rgba(129,140,248,0.4); }
.modal-body { font-size: 14px; color: #94a3b8; line-height: 1.7; }
.modal-body h3 { color: #a5b4fc; margin: 18px 0 8px 0; font-size: 14px; font-weight: 600; }
.modal-body ul { margin: 0 0 12px 0; padding-left: 20px; }
.modal-body li { margin-bottom: 5px; line-height: 1.5; }
.modal-body code { font-family: var(--color-font-mono); font-size: 12px; background: rgba(79,70,229,0.12); color: #a5b4fc; padding: 1px 6px; border-radius: 4px; }
@keyframes fadeIn { from { opacity: 0; transform: translateY(12px); } to { opacity: 1; transform: translateY(0); } }
.fade-in { animation: fadeIn 0.35s ease-out; }
@media (max-width: 640px) { .nav-grid { grid-template-columns: 1fr 1fr; } .hero h1 { font-size: 22px; } .topbar { padding: 0 16px; } .main-container { padding: 0 16px; } }
@media (max-width: 420px) { .nav-grid { grid-template-columns: 1fr; } }
</style>
</head>
<body>
<nav class="topbar" role="banner">
    <svg class="topbar-logo" xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100" aria-label="WireMock">
        <rect width="100" height="100" fill="#1E1B4B" rx="18"/>
        <line x1="18" y1="25" x2="34" y2="68" stroke="#818CF8" stroke-width="5" stroke-linecap="round"/>
        <line x1="34" y1="68" x2="50" y2="45" stroke="#818CF8" stroke-width="5" stroke-linecap="round"/>
        <line x1="50" y1="45" x2="66" y2="68" stroke="#818CF8" stroke-width="5" stroke-linecap="round"/>
        <line x1="66" y1="68" x2="82" y2="25" stroke="#818CF8" stroke-width="5" stroke-linecap="round"/>
        <line x1="26" y1="46" x2="74" y2="46" stroke="#F97316" stroke-width="2.5" stroke-linecap="round" stroke-dasharray="4 3"/>
        <circle cx="18" cy="25" r="6" fill="#F97316"/>
        <circle cx="34" cy="68" r="5" fill="#A5B4FC"/>
        <circle cx="50" cy="45" r="6.5" fill="#F97316"/>
        <circle cx="66" cy="68" r="5" fill="#A5B4FC"/>
        <circle cx="82" cy="25" r="6" fill="#F97316"/>
    </svg>
    <div>
        <div class="topbar-title">WireMock Mock Server</div>
        <div class="topbar-sub">Service virtualization for testing</div>
    </div>
</nav>
<div class="main-container fade-in" role="main">
    <div class="hero">
        <svg class="hero-logo" xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100" aria-hidden="true">
            <rect width="100" height="100" fill="#1E1B4B" rx="18"/>
            <line x1="18" y1="25" x2="34" y2="68" stroke="#818CF8" stroke-width="5" stroke-linecap="round"/>
            <line x1="34" y1="68" x2="50" y2="45" stroke="#818CF8" stroke-width="5" stroke-linecap="round"/>
            <line x1="50" y1="45" x2="66" y2="68" stroke="#818CF8" stroke-width="5" stroke-linecap="round"/>
            <line x1="66" y1="68" x2="82" y2="25" stroke="#818CF8" stroke-width="5" stroke-linecap="round"/>
            <line x1="26" y1="46" x2="74" y2="46" stroke="#F97316" stroke-width="2.5" stroke-linecap="round" stroke-dasharray="4 3"/>
            <circle cx="18" cy="25" r="6" fill="#F97316"/>
            <circle cx="34" cy="68" r="5" fill="#A5B4FC"/>
            <circle cx="50" cy="45" r="6.5" fill="#F97316"/>
            <circle cx="66" cy="68" r="5" fill="#A5B4FC"/>
            <circle cx="82" cy="25" r="6" fill="#F97316"/>
        </svg>
        <h1>WireMock Mock Server
            <button class="info-btn" aria-label="Show information" onclick="toggleInfoModal()">
                <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="10"/><line x1="12" y1="16" x2="12" y2="12"/><line x1="12" y1="8" x2="12.01" y2="8"/></svg>
            </button>
        </h1>
        <p>WireMock-based service virtualization for performance and integration testing</p>
    </div>
    <div class="section-label">Quick Links</div>
    <nav class="nav-grid" aria-label="Features navigation">
        <a class="nav-card" href="/__admin/dashboard">
            <span class="card-icon" aria-hidden="true"><svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="3" width="7" height="7"/><rect x="14" y="3" width="7" height="7"/><rect x="14" y="14" width="7" height="7"/><rect x="3" y="14" width="7" height="7"/></svg></span>
            <h3>Dashboard</h3>
            <p>Create, edit and manage mock stubs with a visual interface</p>
        </a>
        <a class="nav-card" href="/__admin/mappings">
            <span class="card-icon" aria-hidden="true"><svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="16 18 22 12 16 6"/><polyline points="8 6 2 12 8 18"/></svg></span>
            <h3>Admin API</h3>
            <p>Raw JSON view of all registered stub mappings</p>
        </a>
        <a class="nav-card" href="/__admin/stub-counter">
            <span class="card-icon" aria-hidden="true"><svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><line x1="18" y1="20" x2="18" y2="10"/><line x1="12" y1="20" x2="12" y2="4"/><line x1="6" y1="20" x2="6" y2="14"/></svg></span>
            <h3>Statistics</h3>
            <p>Request counters grouped by stub pattern and URL</p>
        </a>
        <a class="nav-card" href="https://wiremock.org/docs/" target="_blank" rel="noopener">
            <span class="card-icon" aria-hidden="true"><svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M2 3h6a4 4 0 0 1 4 4v14a3 3 0 0 0-3-3H2z"/><path d="M22 3h-6a4 4 0 0 0-4 4v14a3 3 0 0 1 3-3h7z"/></svg></span>
            <h3>WireMock Docs</h3>
            <p>Official documentation and API reference</p>
        </a>
    </nav>
    <div class="section-label">About</div>
    <div class="info-card">
        <div class="info-card-h">Service Virtualization for Testing</div>
        <div class="info-card-c">
            <p>WireMock is an HTTP mock server that simulates APIs your application depends on, enabling testing without relying on real external services.</p>
            <ul>
                <li><strong>Performance testing</strong> &mdash; Simulate backend responses with controlled latency</li>
                <li><strong>Integration testing</strong> &mdash; Mock third-party APIs and downstream services</li>
                <li><strong>Fault injection</strong> &mdash; Simulate errors, timeouts, and slow responses</li>
                <li><strong>Development</strong> &mdash; Work against a mock API before the real service is built</li>
            </ul>
        </div>
    </div>
    <div class="info-card">
        <div class="info-card-h">How This Server Works</div>
        <div class="info-card-c">
            <p>Stubs can be managed visually via the <a href="/__admin/dashboard">Dashboard</a>, programmatically via the <a href="/__admin/mappings">REST API</a>, or by placing JSON files in the <code>/mappings</code> directory.</p>
        </div>
    </div>
    <div class="section-label">API Reference</div>
    <div class="info-card" style="padding: 0;">
        <div class="table-scroll">
            <table class="api-table" role="table" aria-label="API endpoints">
                <thead><tr><th scope="col">Method</th><th scope="col">Endpoint</th><th scope="col">Description</th></tr></thead>
                <tbody>
                    <tr><td><span class="method get">GET</span></td><td class="path">/__admin/mappings</td><td>List all stub mappings</td></tr>
                    <tr><td><span class="method post">POST</span></td><td class="path">/__admin/mappings</td><td>Create a new stub mapping</td></tr>
                    <tr><td><span class="method post">POST</span></td><td class="path">/__admin/mappings/import</td><td>Bulk import stubs</td></tr>
                    <tr><td><span class="method delete">DELETE</span></td><td class="path">/__admin/mappings</td><td>Delete all stub mappings</td></tr>
                    <tr><td><span class="method get">GET</span></td><td class="path">/__admin/stub-counter</td><td>Request statistics</td></tr>
                    <tr><td><span class="method get">GET</span></td><td class="path">/__admin/response-times</td><td>Response time statistics</td></tr>
                    <tr><td><span class="method get">GET</span></td><td class="path">/__admin/server-metrics</td><td>JVM metrics</td></tr>
                    <tr><td><span class="method post">POST</span></td><td class="path">/__admin/reset-stub-counter</td><td>Reset all counters</td></tr>
                    <tr><td><span class="method get">GET</span></td><td class="path">/__admin/dashboard</td><td>Open mock dashboard</td></tr>
                </tbody>
            </table>
        </div>
    </div>
    <footer class="footer" role="contentinfo">
        WireMock Mock Server &middot; Powered by <a href="https://wiremock.org" target="_blank" rel="noopener">WireMock</a>
    </footer>
</div>
<div id="infoModal" class="modal-overlay hidden" onclick="if(event.target===this)toggleInfoModal()" role="dialog" aria-modal="true" aria-labelledby="modal-title">
    <div class="modal-content">
        <div class="modal-header">
            <h2 id="modal-title">About WireMock Mock Server</h2>
            <button class="modal-close" onclick="toggleInfoModal()" aria-label="Close">&#10005;</button>
        </div>
        <div class="modal-body">
            <p>A WireMock-based mock server with custom extensions for HTTP stub management and monitoring.</p>
            <h3>Key features</h3>
            <ul>
                <li><strong>Request matching:</strong> URL patterns, headers, query parameters, body matchers</li>
                <li><strong>Response templating:</strong> Dynamic responses using Handlebars templates</li>
                <li><strong>Fault simulation:</strong> Delays, connection resets, chunked responses</li>
                <li><strong>Analytics:</strong> Per-stub and per-URL request counters</li>
            </ul>
        </div>
    </div>
</div>
<script>
(function() {
    'use strict';
    function toggleInfoModal() {
        var modal = document.getElementById('infoModal');
        if (!modal) return;
        modal.classList.toggle('hidden');
    }
    window.toggleInfoModal = toggleInfoModal;
    document.addEventListener('keydown', function(e) {
        if (e.key === 'Escape') {
            var modal = document.getElementById('infoModal');
            if (modal && !modal.classList.contains('hidden')) toggleInfoModal();
        }
    });
})();
</script>
</body>
</html>
""";
}
