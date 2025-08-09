package com.example.scanner.core;

import jakarta.enterprise.context.ApplicationScoped;

import java.net.InetAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@ApplicationScoped
public class NetworkScanner {

    public record DeviceInfo(String ip, String hostname, long pingMillis, boolean reachable) {}

    public static class Progress {
        public final int total;
        public final AtomicInteger done = new AtomicInteger(0);
        public final List<DeviceInfo> partial = Collections.synchronizedList(new ArrayList<>());
        public volatile boolean finished = false;

        Progress(int total) {
            this.total = total;
        }

        public int percent() {
            if (total == 0) return 100;
            int p = Math.round(100f * done.get() / total);
            return Math.min(100, Math.max(0, p));
        }
    }

    private final ConcurrentMap<String, Progress> jobs = new ConcurrentHashMap<>();
    private final ExecutorService pool =
            Executors.newFixedThreadPool(Math.max(4, Runtime.getRuntime().availableProcessors()));

    public static class JobNotFound extends RuntimeException {}

    public static class JobProgress {
        public int percent;
        public boolean finished;
        public List<DeviceInfo> results;

        public JobProgress() {}
        public JobProgress(int percent, boolean finished, List<DeviceInfo> results) {
            this.percent = percent;
            this.finished = finished;
            this.results = results;
        }
    }

    /** Start a new async scan and return a job id. */
    public String start(String cidr, int timeoutMs) {
        List<String> ips = expandCidr(cidr);
        Progress progress = new Progress(ips.size());
        String jobId = UUID.randomUUID().toString();
        jobs.put(jobId, progress);

        pool.submit(() -> doScan(ips, timeoutMs, progress, jobId));
        return jobId;
    }

    /** Poll progress by job id. */
    public JobProgress progress(String jobId) {
        Progress p = jobs.get(jobId);
        if (p == null) throw new JobNotFound();
        return new JobProgress(p.percent(), p.finished, new ArrayList<>(p.partial));
    }

    // ---------- scanning ----------

    private void doScan(List<String> ips, int timeoutMs, Progress progress, String jobId) {
        try {
            List<Callable<Void>> tasks = new ArrayList<>(ips.size());
            for (String ip : ips) {
                tasks.add(() -> {
                    long start = System.nanoTime();
                    boolean reachable = false;
                    long pingMs;

                    try {
                        InetAddress addr = InetAddress.getByName(ip);
                        reachable = addr.isReachable(timeoutMs);
                        // Only count ping time if reachable; otherwise mark as -1
                        pingMs = reachable
                                ? Duration.ofNanos(System.nanoTime() - start).toMillis()
                                : -1L;
                    } catch (Exception e) {
                        pingMs = -1L;
                    }

                    // Resolve hostname even if not reachable
                    String hostname = resolveHostname(ip);
                    if (hostname == null || hostname.isBlank()) {
                        hostname = "(unknown)";
                    }

                    progress.partial.add(new DeviceInfo(ip, hostname, pingMs, reachable));
                    progress.done.incrementAndGet();
                    return null;
                });
            }

            // limit parallelism to something sane for local scanning
            int threads = Math.min(64, Math.max(8, Runtime.getRuntime().availableProcessors() * 2));
            ExecutorService local = Executors.newFixedThreadPool(threads);
            try {
                List<Future<Void>> futures = new ArrayList<>(tasks.size());
                for (Callable<Void> t : tasks) futures.add(local.submit(t));
                for (Future<Void> f : futures) {
                    try {
                        f.get();
                    } catch (ExecutionException | InterruptedException ignored) {
                        // continue; individual failures are fine
                    }
                }
            } finally {
                local.shutdownNow();
            }
        } finally {
            progress.finished = true;
            // keep job around briefly so UI can fetch final state, then drop it
            pool.submit(() -> {
                try {
                    Thread.sleep(30_000);
                } catch (InterruptedException ignored) {}
                jobs.remove(jobId);
            });
        }
    }

    // ---------- CIDR helpers ----------

    private static final Pattern CIDR =
            Pattern.compile("^([0-9]{1,3}(?:\\.[0-9]{1,3}){3})/(\\d{1,2})$");

    static List<String> expandCidr(String cidr) {
        Matcher m = CIDR.matcher(cidr == null ? "" : cidr.trim());
        if (!m.matches()) return List.of();
        String base = m.group(1);
        int prefix = Integer.parseInt(m.group(2));
        if (prefix < 0 || prefix > 32) return List.of();

        long baseIp = ipv4ToLong(base);
        long mask = prefix == 0 ? 0 : ~((1L << (32 - prefix)) - 1) & 0xffffffffL;
        long network = baseIp & mask;
        long broadcast = network | ~mask & 0xffffffffL;

        // exclude network and broadcast for /0–/30
        long start = (prefix <= 30) ? network + 1 : network;
        long end = (prefix <= 30) ? broadcast - 1 : broadcast;

        List<String> out = new ArrayList<>();
        for (long ip = start; ip <= end; ip++) out.add(longToIpv4(ip));
        return out;
    }

    private static long ipv4ToLong(String ip) {
        String[] p = ip.split("\\.");
        long v = 0;
        for (String s : p) v = (v << 8) | (Integer.parseInt(s) & 0xff);
        return v & 0xffffffffL;
    }

    private static String longToIpv4(long v) {
        return ((v >> 24) & 0xff) + "." + ((v >> 16) & 0xff) + "." + ((v >> 8) & 0xff) + "." + (v & 0xff);
    }

    // ---------- Hostname resolution ----------
    private static String resolveHostname(String ip) {
        try {
            InetAddress addr = InetAddress.getByName(ip);

            // 1) canonical host (reverse DNS)
            String h1 = addr.getCanonicalHostName();
            if (h1 != null && !h1.equalsIgnoreCase(ip)) return h1;

            // 2) explicit reverse lookup
            InetAddress byAddr = InetAddress.getByAddress(addr.getAddress());
            String h2 = byAddr.getHostName();
            if (h2 != null && !h2.equalsIgnoreCase(ip)) return h2;
        } catch (Exception ignored) {
        }
        return "";
    }
}