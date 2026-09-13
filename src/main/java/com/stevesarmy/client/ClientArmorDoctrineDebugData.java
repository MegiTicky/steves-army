package com.stevesarmy.client;

import com.stevesarmy.network.ArmorDoctrineDebugPacket;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class ClientArmorDoctrineDebugData {
    public static final ClientArmorDoctrineDebugData INSTANCE = new ClientArmorDoctrineDebugData();

    private volatile int mode;
    private volatile List<ArmorDoctrineDebugPacket.Contact> contacts = List.of();
    private volatile Map<UUID, ArmorDoctrineDebugPacket.Soldier> soldiersById = Map.of();

    private ClientArmorDoctrineDebugData() {}

    public void receive(ArmorDoctrineDebugPacket packet) {
        mode = packet.mode();
        contacts = packet.contacts();
        soldiersById = packet.soldiers().stream()
            .collect(Collectors.toMap(ArmorDoctrineDebugPacket.Soldier::soldierId, Function.identity()));
    }

    public int mode() { return mode; }
    public List<ArmorDoctrineDebugPacket.Contact> contacts() { return contacts; }
    public Map<UUID, ArmorDoctrineDebugPacket.Soldier> soldiersById() { return soldiersById; }

    public void clear() {
        mode = 0;
        contacts = List.of();
        soldiersById = Map.of();
    }
}
