package com.stevesarmy.client;

import com.stevesarmy.network.SuppressPingDebugPacket;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class ClientSuppressPingDebugData {
    public static final ClientSuppressPingDebugData INSTANCE = new ClientSuppressPingDebugData();

    /** Snapshots stop arriving once a soldier's ping ends; age them out. */
    private static final long SNAPSHOT_TTL_MS = 3000;

    private static final class Entry {
        final SuppressPingDebugPacket packet;
        final long receivedAtMs;
        Entry(SuppressPingDebugPacket packet, long receivedAtMs) {
            this.packet = packet;
            this.receivedAtMs = receivedAtMs;
        }
    }

    private volatile boolean renderEnabled;
    private final Map<UUID, Entry> snapshotsById = new ConcurrentHashMap<>();

    private ClientSuppressPingDebugData() {}

    public void receive(SuppressPingDebugPacket packet) {
        this.renderEnabled = packet.renderEnabled();
        snapshotsById.put(packet.soldierId(), new Entry(packet, System.currentTimeMillis()));
    }

    public boolean renderEnabled() { return renderEnabled; }

    public List<SuppressPingDebugPacket> freshSnapshots() {
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
