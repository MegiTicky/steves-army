package com.stevesarmy.client;

import com.stevesarmy.network.FireTeamSuppressionSyncPacket;
import java.util.List;

public final class ClientFireTeamSuppressionData {
    public static final ClientFireTeamSuppressionData INSTANCE = new ClientFireTeamSuppressionData();
    private volatile List<FireTeamSuppressionSyncPacket.Entry> entries = List.of();
    private ClientFireTeamSuppressionData() {}
    public void update(List<FireTeamSuppressionSyncPacket.Entry> e) { entries = List.copyOf(e); }
    public void clear() { entries = List.of(); }
    public List<FireTeamSuppressionSyncPacket.Entry> getEntries() { return entries; }
}
