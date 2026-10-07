package com.stevesarmy.client;

import com.stevesarmy.network.DangerMapDebugPacket;

import java.util.List;

public final class ClientDangerMapDebugData {
    public static final ClientDangerMapDebugData INSTANCE = new ClientDangerMapDebugData();

    private volatile boolean renderEnabled;
    private volatile List<DangerMapDebugPacket.Cell> cells = List.of();
    private volatile List<DangerMapDebugPacket.Threat> threats = List.of();
    private volatile List<DangerMapDebugPacket.Corridor> corridors = List.of();
    private volatile DangerMapDebugPacket.Stats stats =
        new DangerMapDebugPacket.Stats(0, 0, 0, 0, 0, 0);

    private ClientDangerMapDebugData() {}

    public void receive(DangerMapDebugPacket packet) {
        renderEnabled = packet.renderEnabled();
        cells = packet.cells();
        threats = packet.threats();
        corridors = packet.corridors();
        stats = packet.stats();
    }

    public boolean renderEnabled() { return renderEnabled; }
    public List<DangerMapDebugPacket.Cell> cells() { return cells; }
    public List<DangerMapDebugPacket.Threat> threats() { return threats; }
    public List<DangerMapDebugPacket.Corridor> corridors() { return corridors; }
    public DangerMapDebugPacket.Stats stats() { return stats; }

    public void clear() {
        renderEnabled = false;
        cells = List.of();
        threats = List.of();
        corridors = List.of();
        stats = new DangerMapDebugPacket.Stats(0, 0, 0, 0, 0, 0);
    }
}
