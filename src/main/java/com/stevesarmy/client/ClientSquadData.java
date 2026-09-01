package com.stevesarmy.client;

import com.stevesarmy.network.SquadStatusSyncPacket;
import com.stevesarmy.squad.ResupplyConfig;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class ClientSquadData {
    public static final ClientSquadData INSTANCE = new ClientSquadData();

    private final Map<UUID, SquadStatusSyncPacket.SoldierStatusEntry> entries = new ConcurrentHashMap<>();
    private volatile ResupplyConfig resupplyConfig = ResupplyConfig.DEFAULT;
    private long lastUpdateTick = 0;

    public void update(List<SquadStatusSyncPacket.SoldierStatusEntry> newEntries) {
        update(newEntries, ResupplyConfig.DEFAULT);
    }

    public void update(List<SquadStatusSyncPacket.SoldierStatusEntry> newEntries, ResupplyConfig config) {
        entries.clear();
        for (SquadStatusSyncPacket.SoldierStatusEntry entry : newEntries) {
            entries.put(entry.entityId, entry);
        }
        this.resupplyConfig = config;
        lastUpdateTick = System.currentTimeMillis();
    }

    public List<SquadStatusSyncPacket.SoldierStatusEntry> getAllEntries() {
        return Collections.unmodifiableList(entries.values().stream().toList());
    }

    public SquadStatusSyncPacket.SoldierStatusEntry getEntry(UUID entityId) {
        return entries.get(entityId);
    }

    public ResupplyConfig getResupplyConfig() {
        return resupplyConfig;
    }

    public long getLastUpdateTick() {
        return lastUpdateTick;
    }
}