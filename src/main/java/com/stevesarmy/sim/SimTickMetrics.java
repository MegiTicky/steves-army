package com.stevesarmy.sim;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Per-scenario elapsed server-tick timings, excluding setup and cleanup. */
public final class SimTickMetrics {
    private final List<Sample> samples = new ArrayList<>();

    public void reset() {
        samples.clear();
    }

    public void record(int tick, long nanos) {
        samples.add(new Sample(tick, Math.max(0L, nanos)));
    }

    public Snapshot snapshot() {
        List<Sample> sorted = samples.stream().sorted(Comparator.comparingLong(Sample::nanos)).toList();
        long total = 0L;
        int over50 = 0;
        int over100 = 0;
        for (Sample sample : samples) {
            total += sample.nanos;
            if (sample.nanos >= 50_000_000L) over50++;
            if (sample.nanos >= 100_000_000L) over100++;
        }
        List<String> slowestTicks = sorted.stream()
            .skip(Math.max(0, sorted.size() - 10L))
            .sorted(Comparator.comparingInt(Sample::tick))
            .map(sample -> String.format(java.util.Locale.ROOT, "t%d=%.3fms",
                sample.tick, sample.nanos / 1_000_000.0))
            .toList();
        Sample worst = sorted.isEmpty() ? new Sample(-1, 0L) : sorted.get(sorted.size() - 1);
        return new Snapshot(samples.size(), total, percentile(sorted, 0.50).nanos,
            percentile(sorted, 0.95).nanos, percentile(sorted, 0.99).nanos,
            worst.nanos, worst.tick, over50, over100, slowestTicks);
    }

    private static Sample percentile(List<Sample> sorted, double quantile) {
        if (sorted.isEmpty()) return new Sample(-1, 0L);
        int index = Math.min(sorted.size() - 1, Math.max(0, (int) Math.ceil(quantile * sorted.size()) - 1));
        return sorted.get(index);
    }

    private record Sample(int tick, long nanos) {}

    public record Snapshot(int samples, long totalNanos, long p50Nanos, long p95Nanos,
                           long p99Nanos, long maxNanos, int worstTick, int over50Ms, int over100Ms,
                           List<String> slowestTicks) {
        public double averageMs() {
            return samples == 0 ? 0.0 : totalNanos / 1_000_000.0 / samples;
        }

        public double p50Ms() { return p50Nanos / 1_000_000.0; }
        public double p95Ms() { return p95Nanos / 1_000_000.0; }
        public double p99Ms() { return p99Nanos / 1_000_000.0; }
        public double maxMs() { return maxNanos / 1_000_000.0; }

        public String summary() {
            return String.format(java.util.Locale.ROOT,
                "ticks=%d avg=%.3fms p50=%.3fms p95=%.3fms p99=%.3fms max=%.3fms@t%d >=50ms=%d >=100ms=%d slowest=[%s]",
                samples, averageMs(), p50Ms(), p95Ms(), p99Ms(), maxMs(), worstTick, over50Ms, over100Ms,
                String.join(", ", slowestTicks));
        }

        public String json() {
            return String.format(java.util.Locale.ROOT,
                "{\"samples\":%d,\"averageMs\":%.6f,\"p50Ms\":%.6f,\"p95Ms\":%.6f,\"p99Ms\":%.6f,\"maxMs\":%.6f,\"worstTick\":%d,\"over50Ms\":%d,\"over100Ms\":%d,\"slowestTicks\":\"%s\"}",
                samples, averageMs(), p50Ms(), p95Ms(), p99Ms(), maxMs(), worstTick, over50Ms, over100Ms,
                String.join(", ", slowestTicks));
        }
    }
}
