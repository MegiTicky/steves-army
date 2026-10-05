package com.stevesarmy.client;

import com.stevesarmy.network.CoverStateDebugPacket;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class ClientCoverStateDebugData {
    public static final ClientCoverStateDebugData INSTANCE = new ClientCoverStateDebugData();

    /** Server refreshes subscribed soldiers every 10 ticks; age out gaps. */
    private static final long SNAPSHOT_TTL_MS = 3000;

    private static final class Entry {
        final CoverStateDebugPacket packet;
        final long receivedAtMs;
        Entry(CoverStateDebugPacket packet, long receivedAtMs) {
            this.packet = packet;
            this.receivedAtMs = receivedAtMs;
        }
    }

    private volatile boolean renderEnabled;
    private final Map<UUID, Entry> snapshotsById = new ConcurrentHashMap<>();

    private ClientCoverStateDebugData() {}

    public void receive(CoverStateDebugPacket packet) {
        this.renderEnabled = packet.renderEnabled();
        snapshotsById.put(packet.soldierId(), new Entry(packet, System.currentTimeMillis()));
    }

    public boolean renderEnabled() { return renderEnabled; }

    public List<CoverStateDebugPacket> freshSnapshots() {
        long now = System.currentTimeMillis();
        return snapshotsById.values().stream()
            .filter(e -> now - e.receivedAtMs < SNAPSHOT_TTL_MS)
            .map(e -> e.packet)
            .toList();
    }

    public void clear() {
        renderEnabled = false;
        snapshotsById.clear();
    }
}
