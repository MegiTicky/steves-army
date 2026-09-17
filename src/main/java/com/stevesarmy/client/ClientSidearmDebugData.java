package com.stevesarmy.client;

import com.stevesarmy.network.SidearmDebugPacket;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class ClientSidearmDebugData {
    public static final ClientSidearmDebugData INSTANCE = new ClientSidearmDebugData();

    private volatile boolean renderEnabled;
    private volatile Map<UUID, SidearmDebugPacket.Soldier> soldiersById = Map.of();

    private ClientSidearmDebugData() {}

    public void receive(SidearmDebugPacket packet) {
        renderEnabled = packet.renderEnabled();
        soldiersById = packet.soldiers().stream()
            .collect(Collectors.toMap(SidearmDebugPacket.Soldier::soldierId, Function.identity()));
    }

    public boolean renderEnabled() { return renderEnabled; }
    public Map<UUID, SidearmDebugPacket.Soldier> soldiersById() { return soldiersById; }

    public void clear() {
        renderEnabled = false;
        soldiersById = Map.of();
    }
}
