package se.ostberg.wiremock.extension;

import com.github.tomakehurst.wiremock.admin.Router;
import com.github.tomakehurst.wiremock.extension.AdminApiExtension;
import com.github.tomakehurst.wiremock.http.RequestMethod;
import com.github.tomakehurst.wiremock.http.ResponseDefinition;
import com.github.tomakehurst.wiremock.common.Json;

import java.lang.management.*;
import java.util.*;

/**
 * Exposes JVM metrics (CPU, memory, threads, GC, uptime) via admin API.
 * Uses only built-in JDK MXBeans — zero external dependencies, zero overhead.
 */
public class ServerMetricsExtension implements AdminApiExtension {

    @Override
    public String getName() {
        return "wiremock-server-metrics";
    }

    @Override
    public void contributeAdminApiRoutes(Router router) {
        router.add(RequestMethod.GET, "/server-metrics", (admin, serveEvent, pathParams) -> {
            Map<String, Object> metrics = new LinkedHashMap<>();

            // Memory
            MemoryMXBean memBean = ManagementFactory.getMemoryMXBean();
            MemoryUsage heap = memBean.getHeapMemoryUsage();
            MemoryUsage nonHeap = memBean.getNonHeapMemoryUsage();

            Map<String, Object> memory = new LinkedHashMap<>();
            Map<String, Object> heapMap = new LinkedHashMap<>();
            heapMap.put("usedMB", heap.getUsed() / (1024 * 1024));
            heapMap.put("committedMB", heap.getCommitted() / (1024 * 1024));
            heapMap.put("maxMB", heap.getMax() > 0 ? heap.getMax() / (1024 * 1024) : -1);
            heapMap.put("usagePercent", heap.getMax() > 0
                ? Math.round((double) heap.getUsed() / heap.getMax() * 100) : -1);
            memory.put("heap", heapMap);

            Map<String, Object> nonHeapMap = new LinkedHashMap<>();
            nonHeapMap.put("usedMB", nonHeap.getUsed() / (1024 * 1024));
            nonHeapMap.put("committedMB", nonHeap.getCommitted() / (1024 * 1024));
            memory.put("nonHeap", nonHeapMap);
            metrics.put("memory", memory);

            // CPU
            OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();
            Map<String, Object> cpu = new LinkedHashMap<>();
            cpu.put("availableProcessors", osBean.getAvailableProcessors());
            cpu.put("systemLoadAverage", osBean.getSystemLoadAverage());

            // Try to get process CPU load via com.sun API (available on HotSpot/OpenJDK)
            if (osBean instanceof com.sun.management.OperatingSystemMXBean sunBean) {
                double processCpu = sunBean.getProcessCpuLoad();
                cpu.put("processCpuPercent", processCpu >= 0 ? Math.round(processCpu * 100) : -1);
                double systemCpu = sunBean.getCpuLoad();
                cpu.put("systemCpuPercent", systemCpu >= 0 ? Math.round(systemCpu * 100) : -1);
            }
            metrics.put("cpu", cpu);

            // Threads
            ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
            Map<String, Object> threads = new LinkedHashMap<>();
            threads.put("current", threadBean.getThreadCount());
            threads.put("peak", threadBean.getPeakThreadCount());
            threads.put("daemon", threadBean.getDaemonThreadCount());
            threads.put("totalStarted", threadBean.getTotalStartedThreadCount());
            metrics.put("threads", threads);

            // GC
            List<Map<String, Object>> gcList = new ArrayList<>();
            for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
                Map<String, Object> gcMap = new LinkedHashMap<>();
                gcMap.put("name", gc.getName());
                gcMap.put("collections", gc.getCollectionCount());
                gcMap.put("totalTimeMs", gc.getCollectionTime());
                gcList.add(gcMap);
            }
            metrics.put("gc", gcList);

            // Runtime
            RuntimeMXBean rtBean = ManagementFactory.getRuntimeMXBean();
            Map<String, Object> runtime = new LinkedHashMap<>();
            long uptimeMs = rtBean.getUptime();
            runtime.put("uptimeMs", uptimeMs);
            runtime.put("uptimeFormatted", formatUptime(uptimeMs));
            runtime.put("vmName", rtBean.getVmName());
            runtime.put("vmVersion", rtBean.getVmVersion());
            runtime.put("pid", ProcessHandle.current().pid());
            metrics.put("runtime", runtime);

            return new ResponseDefinition(200, Json.write(metrics));
        });
    }

    private static String formatUptime(long ms) {
        long seconds = ms / 1000;
        long days = seconds / 86400;
        long hours = (seconds % 86400) / 3600;
        long minutes = (seconds % 3600) / 60;
        long secs = seconds % 60;

        if (days > 0) return days + "d " + hours + "h " + minutes + "m";
        if (hours > 0) return hours + "h " + minutes + "m " + secs + "s";
        if (minutes > 0) return minutes + "m " + secs + "s";
        return secs + "s";
    }
}
