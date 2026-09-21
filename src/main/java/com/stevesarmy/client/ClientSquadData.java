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
    private volatile UUID lastSwappedSoldierId = null;
    private long lastUpdateTick = 0;

    public void update(List<SquadStatusSyncPacket.SoldierStatusEntry> newEntries) {
        update(newEntries, ResupplyConfig.DEFAULT, null);
    }

    public void update(List<SquadStatusSyncPacket.SoldierStatusEntry> newEntries, ResupplyConfig config) {
        update(newEntries, config, null);
    }

    public void update(List<SquadStatusSyncPacket.SoldierStatusEntry> newEntries, ResupplyConfig config, UUID lastSwapped) {
        entries.clear();
        for (SquadStatusSyncPacket.SoldierStatusEntry entry : newEntries) {
            entries.put(entry.entityId, entry);
        }
        this.resupplyConfig = config;
        this.lastSwappedSoldierId = lastSwapped;
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

    /** The player's previous swap body (may be dead/unloaded by now), or null when never swapped. */
    public UUID getLastSwappedSoldierId() {
        return lastSwappedSoldierId;
    }

    public long getLastUpdateTick() {
        return lastUpdateTick;
    }
}