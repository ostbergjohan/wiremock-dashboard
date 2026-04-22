package se.ostberg.wiremock.extension;

import com.github.tomakehurst.wiremock.admin.Router;
import com.github.tomakehurst.wiremock.extension.AdminApiExtension;
import com.github.tomakehurst.wiremock.http.RequestMethod;
import com.github.tomakehurst.wiremock.http.ResponseDefinition;
import com.github.tomakehurst.wiremock.common.Json;
import java.util.*;
import java.util.stream.Collectors;

public class StubCounterAdminTask implements AdminApiExtension {
    
    @Override
    public String getName() {
        return "wiremock_mock-counter-admin";
    }
    
    @Override
    public void contributeAdminApiRoutes(Router router) {
        // GET /stub-counter - get mock counts sorted by hits (most to least)
        router.add(RequestMethod.GET, "/stub-counter", (admin, serveEvent, pathParams) -> {
            Map<String, Long> counts = StubCounterExtension.getStubCounts();
            
            // Sort by count descending
            List<Map.Entry<String, Long>> sortedList = counts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .collect(Collectors.toList());
            
            Map<String, Long> sortedCounts = new LinkedHashMap<>();
            for (Map.Entry<String, Long> entry : sortedList) {
                sortedCounts.put(entry.getKey(), entry.getValue());
            }
            
            long total = counts.values().stream().mapToLong(Long::longValue).sum();
            
            Map<String, Object> response = new HashMap<>();
            response.put("counts", sortedCounts);
            response.put("total", total);
            
            return new ResponseDefinition(200, Json.write(response));
        });
        
        // GET /stub-counter/urls - get exact URL counts sorted
        router.add(RequestMethod.GET, "/stub-counter/urls", (admin, serveEvent, pathParams) -> {
            Map<String, Long> counts = StubCounterExtension.getUrlCounts();
            
            // Sort by count descending
            List<Map.Entry<String, Long>> sortedList = counts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .collect(Collectors.toList());
            
            Map<String, Long> sortedCounts = new LinkedHashMap<>();
            for (Map.Entry<String, Long> entry : sortedList) {
                sortedCounts.put(entry.getKey(), entry.getValue());
            }
            
            long total = counts.values().stream().mapToLong(Long::longValue).sum();
            
            Map<String, Object> response = new HashMap<>();
            response.put("counts", sortedCounts);
            response.put("total", total);
            
            return new ResponseDefinition(200, Json.write(response));
        });
        
        // GET /stub-counter/summary - text format with percentages
        router.add(RequestMethod.GET, "/stub-counter/summary", (admin, serveEvent, pathParams) -> {
            Map<String, Long> counts = StubCounterExtension.getStubCounts();
            long total = counts.values().stream().mapToLong(Long::longValue).sum();
            
            if (total == 0) {
                return new ResponseDefinition(200, "No requests recorded yet\n");
            }
            
            // Sort by count descending
            List<Map.Entry<String, Long>> sortedList = counts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .collect(Collectors.toList());
            
            StringBuilder text = new StringBuilder();
            text.append(String.format("Total Requests: %d\n", total));
            text.append(String.format("Total Mocks: %d\n\n", counts.size()));
            text.append(String.format("%-80s %10s %10s\n", "Mock", "Count", "Percent"));
            text.append("=".repeat(102)).append("\n");
            
            for (Map.Entry<String, Long> entry : sortedList) {
                long count = entry.getValue();
                double percent = (count * 100.0) / total;
                text.append(String.format("%-80s %10d %9.2f%%\n", 
                    entry.getKey(), count, percent));
            }
            
            return new ResponseDefinition(200, text.toString());
        });

        // GET /response-times - get response time statistics per stub pattern
        router.add(RequestMethod.GET, "/response-times", (admin, serveEvent, pathParams) -> {
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("patterns", ResponseTimeTracker.getPatternTimings());
            response.put("urls", ResponseTimeTracker.getUrlTimings());
            return new ResponseDefinition(200, Json.write(response));
        });
    }
}