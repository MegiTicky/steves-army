package com.stevesarmy.client;

import com.stevesarmy.network.AttackDebugPacket;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class ClientAttackDebugData {
    public static final ClientAttackDebugData INSTANCE = new ClientAttackDebugData();
    private volatile boolean enabled = false;
    private volatile Map<UUID, AttackDebugPacket.Entry> entries = Map.of();

    private ClientAttackDebugData() {}

    public void receivePacket(AttackDebugPacket packet) {
        enabled = packet.isEnabled();
        FireTeamSuppressionOverlay.setVisible(enabled);
        FireTeamSuppressionDebugRenderer.setEnabled(enabled);
        if (!enabled) {
            entries = Map.of();
            return;
        }
        Map<UUID, AttackDebugPacket.Entry> map = new HashMap<>();
        for (AttackDebugPacket.Entry e : packet.getEntries()) {
            map.put(e.soldierUUID(), e);
        }
        entries = map;
    }

    public boolean isEnabled() { return enabled; }

    public Map<UUID, AttackDebugPacket.Entry> getEntries() { return entries; }

    public AttackDebugPacket.Entry getEntry(UUID soldierUUID) {
        return entries.get(soldierUUID);
    }

    public void clear() {
        enabled = false;
        entries = Map.of();
        FireTeamSuppressionOverlay.setVisible(false);
        FireTeamSuppressionDebugRenderer.setEnabled(false);
    }
}
