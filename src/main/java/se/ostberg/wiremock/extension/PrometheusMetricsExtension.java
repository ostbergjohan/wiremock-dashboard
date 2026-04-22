package se.ostberg.wiremock.extension;

import com.github.tomakehurst.wiremock.admin.Router;
import com.github.tomakehurst.wiremock.extension.AdminApiExtension;
import com.github.tomakehurst.wiremock.http.RequestMethod;
import com.github.tomakehurst.wiremock.http.ResponseDefinition;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;

import java.lang.management.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Exposes all WireMock metrics in Prometheus text exposition format.
 *
 * Endpoint: GET /__admin/metrics
 *
 * Includes:
 *   - JVM heap / non-heap memory (bytes)
 *   - CPU load (process + system, 0–100 scale)
 *   - Thread counts
 *   - GC collections and time
 *   - JVM uptime
 *   - Mock stub request counters (per stub pattern)
 *   - Response time stats per stub pattern (min/max/avg in ms)
 */
public class PrometheusMetricsExtension implements AdminApiExtension {

    // CPU delta tracking for container_cpu_usage_percent
    private final AtomicLong lastCpuTimeNs = new AtomicLong(-1);
    private final AtomicLong lastCpuSampleMs = new AtomicLong(-1);

    @Override
    public String getName() {
        return "wiremock-prometheus-metrics";
    }

    @Override
    public void contributeAdminApiRoutes(Router router) {
        router.add(RequestMethod.GET, "/prometheus", (admin, serveEvent, pathParams) -> {
            String body = buildMetricsText();
            return new ResponseDefinitionBuilder()
                .withStatus(200)
                .withHeader("Content-Type", "text/plain; version=0.0.4; charset=utf-8")
                .withBody(body)
                .build();
        });
        router.add(RequestMethod.GET, "/metrics", (admin, serveEvent, pathParams) -> {
            String body = buildMetricsText();
            return new ResponseDefinitionBuilder()
                .withStatus(200)
                .withHeader("Content-Type", "text/plain; version=0.0.4; charset=utf-8")
                .withBody(body)
                .build();
        });
    }

    // -------------------------------------------------------------------------
    // Builder
    // -------------------------------------------------------------------------

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
        MemoryMXBean mem = ManagementFactory.getMemoryMXBean();
        MemoryUsage heap = mem.getHeapMemoryUsage();
        MemoryUsage nonHeap = mem.getNonHeapMemoryUsage();

        gauge(sb, "wiremock_jvm_memory_heap_used_bytes",
            "JVM heap memory used in bytes", heap.getUsed());
        gauge(sb, "wiremock_jvm_memory_heap_committed_bytes",
            "JVM heap memory committed in bytes", heap.getCommitted());
        gauge(sb, "wiremock_jvm_memory_heap_max_bytes",
            "JVM heap memory max in bytes (−1 if undefined)", heap.getMax());
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
            "Number of processors available to the JVM",
            os.getAvailableProcessors());

        gauge(sb, "wiremock_jvm_cpu_system_load_average",
            "System load average for the last minute (−1 if unavailable)",
            os.getSystemLoadAverage());

        if (os instanceof com.sun.management.OperatingSystemMXBean sun) {
            double procCpu = sun.getProcessCpuLoad();
            double sysCpu  = sun.getCpuLoad();
            gauge(sb, "wiremock_jvm_cpu_process_usage_ratio",
                "JVM process CPU load (0.0–1.0, −1 if unavailable)", procCpu);
            gauge(sb, "wiremock_jvm_cpu_system_usage_ratio",
                "System-wide CPU load (0.0–1.0, −1 if unavailable)", sysCpu);
        }
    }

    // -------------------------------------------------------------------------
    // JVM Threads
    // -------------------------------------------------------------------------

    private void appendJvmThreads(StringBuilder sb) {
        ThreadMXBean t = ManagementFactory.getThreadMXBean();

        gauge(sb, "wiremock_jvm_threads_current",
            "Current live thread count", t.getThreadCount());
        gauge(sb, "wiremock_jvm_threads_peak",
            "Peak live thread count since JVM start", t.getPeakThreadCount());
        gauge(sb, "wiremock_jvm_threads_daemon",
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
        RuntimeMXBean rt = ManagementFactory.getRuntimeMXBean();
        List<String> args = rt.getInputArguments();

        double maxRamPct = parseDoubleFlag(args, "MaxRAMPercentage");
        double initRamPct = parseDoubleFlag(args, "InitialRAMPercentage");
        long metaspaceMax = parseSizeFlag(args, "MaxMetaspaceSize");
        long codeCacheReserved = parseSizeFlag(args, "ReservedCodeCacheSize");
        long heapMax = Runtime.getRuntime().maxMemory();
        boolean containerSupport = args.stream().anyMatch(a -> a.contains("+UseContainerSupport"));
        boolean useZgc = args.stream().anyMatch(a -> a.contains("+UseZGC"));
        boolean zgcGenerational = args.stream().anyMatch(a -> a.contains("+ZGenerational"));
        boolean exitOnOom = args.stream().anyMatch(a -> a.contains("+ExitOnOutOfMemoryError"));

        String gcNames = ManagementFactory.getGarbageCollectorMXBeans().stream()
            .map(GarbageCollectorMXBean::getName)
            .reduce((a, b) -> a + ", " + b)
            .orElse("unknown");

        if (maxRamPct >= 0) gauge(sb, "wiremock_jvm_max_ram_percentage",
            "JVM MaxRAMPercentage setting", maxRamPct);
        if (initRamPct >= 0) gauge(sb, "wiremock_jvm_initial_ram_percentage",
            "JVM InitialRAMPercentage setting", initRamPct);
        if (metaspaceMax >= 0) gauge(sb, "wiremock_jvm_metaspace_max_bytes",
            "JVM MaxMetaspaceSize in bytes", metaspaceMax);
        if (codeCacheReserved >= 0) gauge(sb, "wiremock_jvm_code_cache_reserved_bytes",
            "JVM ReservedCodeCacheSize in bytes", codeCacheReserved);
        gauge(sb, "wiremock_jvm_heap_max_bytes",
            "Effective JVM heap max bytes (Runtime.maxMemory)", heapMax);

        // Info gauge — boolean/string flags as labels, value always 1
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
                try {
                    return Double.parseDouble(arg.substring(arg.indexOf('=') + 1));
                } catch (NumberFormatException ignored) {}
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
        // cgroup v2
        Path usageV2 = Path.of("/sys/fs/cgroup/memory.current");
        Path limitV2  = Path.of("/sys/fs/cgroup/memory.max");
        Path swapV2   = Path.of("/sys/fs/cgroup/memory.swap.current");
        // cgroup v1
        Path usageV1 = Path.of("/sys/fs/cgroup/memory/memory.usage_in_bytes");
        Path limitV1  = Path.of("/sys/fs/cgroup/memory/memory.limit_in_bytes");
        Path swapV1   = Path.of("/sys/fs/cgroup/memory/memory.memsw.usage_in_bytes");

        long usage = readLongFile(Files.exists(usageV2) ? usageV2 : usageV1);
        long limit = readLongFile(Files.exists(limitV2) ? limitV2 : limitV1);
        long swap  = readLongFile(Files.exists(swapV2)  ? swapV2  : swapV1);

        if (usage < 0 && limit < 0) return; // not running on Linux with cgroup

        if (usage >= 0) gauge(sb, "container_memory_usage_bytes",
            "Container memory usage in bytes (cgroup)", usage);
        if (limit >= 0) gauge(sb, "container_memory_limit_bytes",
            "Container memory limit in bytes (cgroup)", limit);
        if (swap >= 0) gauge(sb, "container_memory_swap_bytes",
            "Container swap usage in bytes (cgroup, -1 if unavailable)", swap);
    }

    // -------------------------------------------------------------------------
    // cgroup CPU
    // -------------------------------------------------------------------------

    private void appendCgroupCpu(StringBuilder sb) {
        long cpuTimeUs = readCgroupCpuTimeUs();
        long limitCores = readCgroupCpuLimitCores();

        if (cpuTimeUs < 0 && limitCores < 0) return;

        if (limitCores >= 0) gauge(sb, "container_cpu_limit_cores",
            "CPU cores allocated by cgroup quota (-1 = unlimited)", limitCores);

        long nowMs = System.currentTimeMillis();
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
        // cgroup v2: /sys/fs/cgroup/cpu.stat, line "usage_usec <value>"
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
        // cgroup v1
        Path v1 = Path.of("/sys/fs/cgroup/cpuacct/cpuacct.usage");
        if (Files.exists(v1)) {
            long nanos = readLongFile(v1);
            return nanos >= 0 ? nanos / 1000 : -1; // ns → µs
        }
        return -1;
    }

    private long readCgroupCpuLimitCores() {
        // cgroup v2: /sys/fs/cgroup/cpu.max  format: "quota period" or "max period"
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
                return -1; // unlimited
            } catch (Exception ignored) {}
        }
        // cgroup v1
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
            boolean headerWrittenRead  = false;
            boolean headerWrittenWrite = false;
            for (String line : Files.readAllLines(ioStat)) {
                String[] parts = line.split("\\s+");
                if (parts.length < 2) continue;
                String device = parts[0]; // e.g. "8:0"
                long rbytes = -1, wbytes = -1;
                for (int i = 1; i < parts.length; i++) {
                    if (parts[i].startsWith("rbytes=")) rbytes = Long.parseLong(parts[i].substring(7));
                    if (parts[i].startsWith("wbytes=")) wbytes = Long.parseLong(parts[i].substring(7));
                }
                if (rbytes >= 0) {
                    if (!headerWrittenRead) {
                        sb.append("# HELP container_io_read_bytes_total Cumulative bytes read per block device (cgroup v2).\n");
                        sb.append("# TYPE container_io_read_bytes_total counter\n");
                        headerWrittenRead = true;
                    }
                    sb.append("container_io_read_bytes_total{device=\"").append(escapeLabelValue(device))
                      .append("\"} ").append(rbytes).append("\n");
                }
                if (wbytes >= 0) {
                    if (!headerWrittenWrite) {
                        sb.append("\n# HELP container_io_write_bytes_total Cumulative bytes written per block device (cgroup v2).\n");
                        sb.append("# TYPE container_io_write_bytes_total counter\n");
                        headerWrittenWrite = true;
                    }
                    sb.append("container_io_write_bytes_total{device=\"").append(escapeLabelValue(device))
                      .append("\"} ").append(wbytes).append("\n");
                }
            }
            if (headerWrittenRead || headerWrittenWrite) sb.append("\n");
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
                "Resident Set Size — actual RAM used by process", rss * 1024);
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
        // Format: "VmRSS:   12345 kB"
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
    // Mock stub counters
    // -------------------------------------------------------------------------

    private void appendStubCounters(StringBuilder sb) {
        Map<String, Long> counts = StubCounterExtension.getStubCounts();
        if (counts.isEmpty()) {
            return;
        }

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
    // Response times
    // -------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private void appendResponseTimes(StringBuilder sb) {
        Map<String, Map<String, Object>> timings = ResponseTimeTracker.getPatternTimings();
        if (timings.isEmpty()) {
            return;
        }

        writeLabelledGauge(sb,
            "wiremock_stub_response_time_min_ms",
            "Minimum response time in ms per stub pattern",
            timings, "min");

        writeLabelledGauge(sb,
            "wiremock_stub_response_time_max_ms",
            "Maximum response time in ms per stub pattern",
            timings, "max");

        writeLabelledGauge(sb,
            "wiremock_stub_response_time_avg_ms",
            "Average response time in ms per stub pattern",
            timings, "avg");

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

    /**
     * Escapes a string for use as a Prometheus label value.
     * Spec: backslash, double-quote and newline must be escaped.
     */
    private String escapeLabelValue(String value) {
        return value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n");
    }
}
