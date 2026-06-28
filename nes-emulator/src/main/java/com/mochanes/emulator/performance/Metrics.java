package com.mochanes.emulator.performance;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class Metrics {
    private static final Metrics INSTANCE = new Metrics();
    private final ConcurrentHashMap<String, MetricInterim> interimStats = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, MetricStats> finalizedStats = new ConcurrentHashMap<>();

    private static volatile boolean ENABLED = false;

    private Metrics() {
    }

    public static Metrics getInstance() {
        return INSTANCE;
    }

    public static void setEnabled(boolean enabled) {
        ENABLED = enabled;
    }

    public static long start() {
        if (!ENABLED)
            return 0;
        return System.nanoTime();
    }

    public static void measure(String name, long startTimeNano) {
        if (startTimeNano == 0)
            return;
        long duration = System.nanoTime() - startTimeNano;
        INSTANCE.record(name, duration);
    }

    // For functional blocks
    public static void run(String name, Runnable block) {
        long start = System.nanoTime();
        try {
            block.run();
        } finally {
            measure(name, start);
        }
    }

    private void record(String name, long durationNano) {
        interimStats.computeIfAbsent(name, k -> new MetricInterim()).add(durationNano);
    }

    // Called by the Monitor Thread to snapshot current stats
    public synchronized java.util.Map<String, MetricStats> snapshot() {
        // Merge interim into finalized
        interimStats.forEach((name, interim) -> {
            MetricStats stats = finalizedStats.computeIfAbsent(name, k -> new MetricStats(name));
            interim.mergeInto(stats);
        });
        return new java.util.HashMap<>(finalizedStats);
    }

    public static class MetricInterim {
        AtomicLong count = new AtomicLong(0);
        AtomicLong totalTime = new AtomicLong(0);
        AtomicLong maxTime = new AtomicLong(0);

        void add(long time) {
            count.incrementAndGet();
            totalTime.addAndGet(time);
            maxTime.accumulateAndGet(time, Math::max);
        }

        void mergeInto(MetricStats stats) {
            long c = count.getAndSet(0);
            long t = totalTime.getAndSet(0);
            long m = maxTime.getAndSet(0);
            if (c > 0) {
                stats.update(c, t, m);
            }
        }
    }

    public static class MetricStats {
        public final String name;
        public long totalCalls;
        public long totalTimeNano;
        public long maxTimeNano;
        public long lastTimeNano; // last recorded average?

        // Moving Average for display
        public double avgTimeMs;

        public MetricStats(String name) {
            this.name = name;
        }

        public void update(long count, long time, long max) {
            this.totalCalls += count;
            this.totalTimeNano += time;
            if (max > this.maxTimeNano)
                this.maxTimeNano = max;

            // Current interval average
            this.avgTimeMs = (time / (double) count) / 1_000_000.0;
        }
    }
}
