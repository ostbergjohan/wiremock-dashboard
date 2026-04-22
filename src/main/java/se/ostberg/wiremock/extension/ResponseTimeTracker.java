package se.ostberg.wiremock.extension;

import com.github.tomakehurst.wiremock.core.Admin;
import com.github.tomakehurst.wiremock.extension.PostServeAction;
import com.github.tomakehurst.wiremock.common.Timing;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tracks response times per stub pattern (min/max/sum/count).
 * Runs as a PostServeAction after every response is sent,
 * when full Timing data is available.
 */
public class ResponseTimeTracker extends PostServeAction {

    private static final ConcurrentHashMap<String, TimingStats> patternTimings = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, TimingStats> urlTimings = new ConcurrentHashMap<>();

    @Override
    public String getName() {
        return "wiremock-response-time-tracker";
    }

    @Override
    public void doGlobalAction(ServeEvent serveEvent, Admin admin) {
        Timing timing = serveEvent.getTiming();
        if (timing == null) return;

        Integer totalTime = timing.getTotalTime();
        if (totalTime == null) return;

        String method = serveEvent.getRequest().getMethod().toString();
        String url = serveEvent.getRequest().getUrl();

        // Skip root
        if ("/".equals(url)) return;

        long ms = totalTime.longValue();

        // Track by exact URL
        String urlKey = method + ":" + url;
        urlTimings.computeIfAbsent(urlKey, k -> new TimingStats()).record(ms);

        // Track by stub pattern (grouped, same logic as StubCounterExtension)
        String pattern = getStubPattern(method, url);
        patternTimings.computeIfAbsent(pattern, k -> new TimingStats()).record(ms);
    }

    // --- Static accessors ---

    /** Called directly from StubCounterExtension — works regardless of journaling. */
    public static void record(String pattern, String urlKey, long ms) {
        patternTimings.computeIfAbsent(pattern, k -> new TimingStats()).record(ms);
        urlTimings.computeIfAbsent(urlKey, k -> new TimingStats()).record(ms);
    }

    public static Map<String, Map<String, Object>> getPatternTimings() {
        return toMap(patternTimings);
    }

    public static Map<String, Map<String, Object>> getUrlTimings() {
        return toMap(urlTimings);
    }

    public static void resetTimings() {
        patternTimings.clear();
        urlTimings.clear();
    }

    // --- Internal ---

    private static Map<String, Map<String, Object>> toMap(ConcurrentHashMap<String, TimingStats> source) {
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        source.entrySet().stream()
            .sorted((a, b) -> Long.compare(b.getValue().count.get(), a.getValue().count.get()))
            .forEach(e -> result.put(e.getKey(), e.getValue().toMap()));
        return result;
    }

    private String getStubPattern(String method, String url) {
        String[] parts = url.split("\\?", 2);
        String basePath = parts[0];
        if (parts.length > 1) {
            String[] params = parts[1].split("&");
            List<String> paramNames = new ArrayList<>();
            for (String param : params) {
                paramNames.add(param.split("=")[0]);
            }
            Collections.sort(paramNames);
            return method + ":" + basePath + "?" + String.join("&", paramNames) + "=*";
        }
        return method + ":" + basePath;
    }

    /**
     * Lock-free timing statistics using AtomicLong.
     * Values stored in microseconds (µs) for precision.
     * Output converted to milliseconds with one decimal.
     */
    static class TimingStats {
        final AtomicLong min = new AtomicLong(Long.MAX_VALUE);
        final AtomicLong max = new AtomicLong(0);
        final AtomicLong sum = new AtomicLong(0);
        final AtomicLong count = new AtomicLong(0);
        final AtomicLong last = new AtomicLong(0);

        void record(long micros) {
            count.incrementAndGet();
            sum.addAndGet(micros);
            min.accumulateAndGet(micros, Math::min);
            max.accumulateAndGet(micros, Math::max);
            last.set(micros);
        }

        /** Convert µs to ms with one decimal for display. */
        private static double toMs(long micros) {
            return Math.round(micros / 100.0) / 10.0;
        }

        Map<String, Object> toMap() {
            long c = count.get();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("count", c);
            m.put("last", toMs(last.get()));
            m.put("min", min.get() == Long.MAX_VALUE ? 0.0 : toMs(min.get()));
            m.put("max", toMs(max.get()));
            m.put("avg", c > 0 ? toMs(sum.get() / c) : 0.0);
            m.put("total", toMs(sum.get()));
            return m;
        }
    }
}
