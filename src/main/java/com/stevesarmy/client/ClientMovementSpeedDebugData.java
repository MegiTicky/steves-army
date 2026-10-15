package com.stevesarmy.client;

import com.stevesarmy.network.MovementSpeedDebugPacket;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Client-side, short-lived copy of the server's observational movement telemetry. */
public final class ClientMovementSpeedDebugData {
    public static final ClientMovementSpeedDebugData INSTANCE = new ClientMovementSpeedDebugData();

    private static final long EXPIRY_NANOS = 2_000_000_000L;
    private static final long HISTORY_NANOS = 9_000_000_000L;
    private static final int MAX_SNAPSHOTS = 128;
    private static final int HISTORY_SAMPLES = 36;
    private static final int MAX_HISTORY_ENTITIES = 128;

    public record SpeedSample(long receivedAtNanos, double measuredSpeed, double appliedSpeed) {}

    private volatile boolean enabled;
    private volatile List<MovementSpeedDebugPacket.Entry> snapshots = List.of();
    private volatile long receivedAtNanos;
    private final Map<Integer, ArrayDeque<SpeedSample>> history = new HashMap<>();

    private ClientMovementSpeedDebugData() {}

    public synchronized void receive(MovementSpeedDebugPacket packet) {
        enabled = packet.enabled();
        receivedAtNanos = System.nanoTime();
        if (!enabled) {
            snapshots = List.of();
            history.clear();
            return;
        }

        List<MovementSpeedDebugPacket.Entry> entries = packet.entries();
        if (entries.size() > MAX_SNAPSHOTS) entries = entries.subList(0, MAX_SNAPSHOTS);
        snapshots = List.copyOf(entries);
        long now = receivedAtNanos;
        for (MovementSpeedDebugPacket.Entry entry : snapshots) {
            ArrayDeque<SpeedSample> samples = history.computeIfAbsent(entry.entityId(), ignored -> new ArrayDeque<>());
            samples.addLast(new SpeedSample(now, entry.measuredSpeed(), entry.appliedSpeed()));
            while (samples.size() > HISTORY_SAMPLES) samples.removeFirst();
        }
        while (history.size() > MAX_HISTORY_ENTITIES) {
            Integer oldest = history.keySet().iterator().next();
            history.remove(oldest);
        }
    }

    public synchronized boolean renderEnabled() {
        if (enabled && System.nanoTime() - receivedAtNanos > EXPIRY_NANOS) clear();
        return enabled;
    }

    /** Returns the latest batch while it is fresh; stale telemetry is discarded as a batch. */
    public synchronized List<MovementSpeedDebugPacket.Entry> freshSnapshots() {
        if (!renderEnabled()) return List.of();
        return snapshots;
    }

    public synchronized List<SpeedSample> recentHistory(int entityId) {
        if (!renderEnabled()) return List.of();
        ArrayDeque<SpeedSample> samples = history.get(entityId);
        if (samples == null) return List.of();
        long cutoff = System.nanoTime() - HISTORY_NANOS;
        List<SpeedSample> result = new ArrayList<>(samples.size());
        for (SpeedSample sample : samples) {
            if (sample.receivedAtNanos() >= cutoff) result.add(sample);
        }
        return List.copyOf(result);
    }

    public synchronized void clear() {
        enabled = false;
        snapshots = List.of();
        receivedAtNanos = 0;
        history.clear();
    }
}
