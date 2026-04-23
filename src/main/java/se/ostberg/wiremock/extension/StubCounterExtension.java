package se.ostberg.wiremock.extension;

import com.github.tomakehurst.wiremock.extension.ResponseTransformerV2;
import com.github.tomakehurst.wiremock.http.Response;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;

public class StubCounterExtension implements ResponseTransformerV2 {
    
    private static final ConcurrentHashMap<String, AtomicLong> stubCounters = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, AtomicLong> urlCounters = new ConcurrentHashMap<>();
    private static final AtomicBoolean initialized = new AtomicBoolean(false);
    
    @Override
    public Response transform(Response response, ServeEvent serveEvent) {

        // Log on first call
    if (initialized.compareAndSet(false, true)) {
        System.out.println("========================================");
        System.out.println("WIREMOCK MOCK COUNTER PLUGIN ACTIVATED");
        System.out.println("Tracking mock patterns and exact URLs");
        System.out.println("----------------------------------------");
        System.out.println("Admin API:        /__admin");
        System.out.println("Dashboard:        /__admin/dashboard");
        System.out.println("Mock Counter:     /__admin/stub-counter");
        System.out.println("Reset Counter:    /__admin/reset-stub-counter");
        System.out.println("========================================");
    }
        
        String method = serveEvent.getRequest().getMethod().toString();
        String url = serveEvent.getRequest().getUrl();
        
        // Skip root redirect from statistics
        if ("/".equals(url)) {
            return response;
        }
        
        // Count exact URL (with ALL query params and values)
        String urlKey = method + ":" + url;
        urlCounters.computeIfAbsent(urlKey, k -> new AtomicLong(0)).incrementAndGet();
        
        // Count by mock pattern (grouped with param names but not values)
        String stubPattern = getStubPattern(method, url);
        stubCounters.computeIfAbsent(stubPattern, k -> new AtomicLong(0)).incrementAndGet();

        return response;
    }
    
    @Override
    public boolean applyGlobally() {
        return true;
    }
    
    @Override
    public String getName() {
        return "wiremock-mock-counter";
    }
    
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
        // Split URL into base path and query string
        String[] parts = url.split("\\?", 2);
        String basePath = normalizePathSegments(parts[0]);

        if (parts.length > 1) {
            // Has query params - extract parameter names only
            String queryString = parts[1];
            String[] params = queryString.split("&");
            List<String> paramNames = new ArrayList<>();
            
            for (String param : params) {
                String paramName = param.split("=")[0];
                paramNames.add(paramName);
            }
            
            // Sort for consistency (so ?a=x&b=y and ?b=y&a=x group together)
            Collections.sort(paramNames);
            
            // Rebuild with param names only, using * as placeholder
            return method + ":" + basePath + "?" + String.join("&", paramNames) + "=*";
        }

        // No query params
        return method + ":" + basePath;
    }
    
    public static Map<String, Long> getStubCounts() {
        Map<String, Long> result = new HashMap<>();
        stubCounters.forEach((k, v) -> result.put(k, v.get()));
        return result;
    }
    
    public static Map<String, Long> getUrlCounts() {
        Map<String, Long> result = new HashMap<>();
        urlCounters.forEach((k, v) -> result.put(k, v.get()));
        return result;
    }
    
    public static Map<String, Object> getCountsSummary() {
        Map<String, Long> stubs = getStubCounts();
        Map<String, Long> urls = getUrlCounts();
        
        Map<String, Object> result = new HashMap<>();
        result.put("mocks", stubs);
        result.put("urls", urls);
        result.put("totalRequests", stubs.values().stream().mapToLong(Long::longValue).sum());
        result.put("totalMocks", stubs.size());
        result.put("totalUniqueUrls", urls.size());
        
        return result;
    }
    
    public static void resetCounters() {
        stubCounters.clear();
        urlCounters.clear();
        System.out.println("========================================");
        System.out.println("WIREMOCK MOCK COUNTER PLUGIN - COUNTERS RESET");
        System.out.println("All tracking data cleared");
        System.out.println("========================================");
    }
}