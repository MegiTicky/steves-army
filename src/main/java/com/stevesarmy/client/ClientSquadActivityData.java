package com.stevesarmy.client;

import com.stevesarmy.network.SquadActivitySyncPacket;
import com.stevesarmy.squad.FireTeam;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public final class ClientSquadActivityData {
    public static final ClientSquadActivityData INSTANCE = new ClientSquadActivityData();

    private final Map<FireTeam, SquadActivitySyncPacket.ActivityEntry> activities =
        new EnumMap<>(FireTeam.class);

    private ClientSquadActivityData() {
    }

    public synchronized void update(List<SquadActivitySyncPacket.ActivityEntry> entries) {
        activities.clear();
        for (SquadActivitySyncPacket.ActivityEntry entry : entries) {
            activities.put(entry.fireTeam(), entry);
        }
    }

    public synchronized List<SquadActivitySyncPacket.ActivityEntry> getActivities() {
        return Collections.unmodifiableList(new ArrayList<>(activities.values()));
    }

    public synchronized void clear() {
        activities.clear();
    }
}
