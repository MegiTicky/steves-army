package com.stevesarmy.client;

import com.stevesarmy.network.SquadStatusSyncPacket;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class ClientSquadData {
    public static final ClientSquadData INSTANCE = new ClientSquadData();

    private final Map<UUID, SquadStatusSyncPacket.SoldierStatusEntry> entries = new ConcurrentHashMap<>();
    private long lastUpdateTick = 0;

    public void update(List<SquadStatusSyncPacket.SoldierStatusEntry> newEntries) {
        entries.clear();
        for (SquadStatusSyncPacket.SoldierStatusEntry entry : newEntries) {
            entries.put(entry.entityId, entry);
        }
        lastUpdateTick = System.currentTimeMillis();
    }

    public List<SquadStatusSyncPacket.SoldierStatusEntry> getAllEntries() {
        return Collections.unmodifiableList(entries.values().stream().toList());
    }

    public SquadStatusSyncPacket.SoldierStatusEntry getEntry(UUID entityId) {
        return entries.get(entityId);
    }

    public long getLastUpdateTick() {
        return lastUpdateTick;
    }
}