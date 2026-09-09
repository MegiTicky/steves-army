package com.stevesarmy.client;

import com.stevesarmy.network.VehicleCrewDebugPacket;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class ClientVehicleCrewDebugData {
    public static final ClientVehicleCrewDebugData INSTANCE = new ClientVehicleCrewDebugData();

    private volatile int mode;
    private volatile Map<UUID, VehicleCrewDebugPacket.Entry> entries = Map.of();

    private ClientVehicleCrewDebugData() {}

    public void receive(VehicleCrewDebugPacket packet) {
        mode = packet.mode();
        Map<UUID, VehicleCrewDebugPacket.Entry> next = new HashMap<>();
        for (VehicleCrewDebugPacket.Entry entry : packet.entries()) {
            next.put(entry.stationId(), entry);
        }
        entries = Map.copyOf(next);
    }

    public int mode() { return mode; }
    public Map<UUID, VehicleCrewDebugPacket.Entry> entries() { return entries; }

    public void clear() {
        mode = 0;
        entries = Map.of();
    }
}
