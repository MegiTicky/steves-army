package com.stevesarmy.network;

import com.stevesarmy.client.ClientVehicleCrewDebugData;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/** Owner-only debug snapshots for active Tallyho crew stations. */
public final class VehicleCrewDebugPacket {
    private static final int MAX_ENTRIES = 32;
    private static final int MAX_OBSERVATIONS = 10;

    public record Observation(UUID targetId, Vec3 targetPos, int band, boolean visible,
                              boolean detected, float points) {}

    public record Entry(UUID stationId, UUID soldierId, boolean gunner, Vec3 cameraWorld,
                        Vec3 look, UUID targetId, Vec3 targetPos, int state,
                        float aimQuality, float aimError, float bloom, int burstShots,
                        int burstPauseTicks, boolean ready, boolean hasAmmo,
                        UUID suppressionThreatId, Vec3 suppressionAimPos,
                        float focusedRange, float yawLimit, float sweepProgress,
                        List<Observation> observations) {}

    private final int mode;
    private final List<Entry> entries;

    public VehicleCrewDebugPacket(int mode, List<Entry> entries) {
        this.mode = mode;
        this.entries = List.copyOf(entries);
    }

    public VehicleCrewDebugPacket(FriendlyByteBuf buf) {
        mode = buf.readVarInt();
        int count = Math.min(buf.readVarInt(), MAX_ENTRIES);
        List<Entry> decoded = new ArrayList<>(count);
        for (int i = 0; i < count; i++) decoded.add(readEntry(buf));
        entries = List.copyOf(decoded);
    }

    public static void encode(VehicleCrewDebugPacket packet, FriendlyByteBuf buf) {
        buf.writeVarInt(packet.mode);
        buf.writeVarInt(Math.min(packet.entries.size(), MAX_ENTRIES));
        for (int i = 0; i < packet.entries.size() && i < MAX_ENTRIES; i++) writeEntry(buf, packet.entries.get(i));
    }

    private static void writeEntry(FriendlyByteBuf buf, Entry entry) {
        buf.writeUUID(entry.stationId());
        buf.writeUUID(entry.soldierId());
        buf.writeBoolean(entry.gunner());
        writeVec(buf, entry.cameraWorld());
        writeVec(buf, entry.look());
        writeNullableUuid(buf, entry.targetId());
        writeNullableVec(buf, entry.targetPos());
        buf.writeVarInt(entry.state());
        buf.writeFloat(entry.aimQuality());
        buf.writeFloat(entry.aimError());
        buf.writeFloat(entry.bloom());
        buf.writeVarInt(entry.burstShots());
        buf.writeVarInt(entry.burstPauseTicks());
        buf.writeBoolean(entry.ready());
        buf.writeBoolean(entry.hasAmmo());
        writeNullableUuid(buf, entry.suppressionThreatId());
        writeNullableVec(buf, entry.suppressionAimPos());
        buf.writeFloat(entry.focusedRange());
        buf.writeFloat(entry.yawLimit());
        buf.writeFloat(entry.sweepProgress());
        List<Observation> observations = entry.observations();
        buf.writeVarInt(Math.min(observations.size(), MAX_OBSERVATIONS));
        for (int i = 0; i < observations.size() && i < MAX_OBSERVATIONS; i++) {
            Observation observation = observations.get(i);
            buf.writeUUID(observation.targetId());
            writeVec(buf, observation.targetPos());
            buf.writeVarInt(observation.band());
            buf.writeBoolean(observation.visible());
            buf.writeBoolean(observation.detected());
            buf.writeFloat(observation.points());
        }
    }

    private static Entry readEntry(FriendlyByteBuf buf) {
        UUID stationId = buf.readUUID();
        UUID soldierId = buf.readUUID();
        boolean gunner = buf.readBoolean();
        Vec3 camera = readVec(buf);
        Vec3 look = readVec(buf);
        UUID target = readNullableUuid(buf);
        Vec3 targetPos = readNullableVec(buf);
        int state = buf.readVarInt();
        float quality = buf.readFloat();
        float error = buf.readFloat();
        float bloom = buf.readFloat();
        int shots = buf.readVarInt();
        int pause = buf.readVarInt();
        boolean ready = buf.readBoolean();
        boolean ammo = buf.readBoolean();
        UUID suppression = readNullableUuid(buf);
        Vec3 suppressionPos = readNullableVec(buf);
        float range = buf.readFloat();
        float yawLimit = buf.readFloat();
        float sweep = buf.readFloat();
        int count = Math.min(buf.readVarInt(), MAX_OBSERVATIONS);
        List<Observation> observations = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            observations.add(new Observation(buf.readUUID(), readVec(buf), buf.readVarInt(),
                buf.readBoolean(), buf.readBoolean(), buf.readFloat()));
        }
        return new Entry(stationId, soldierId, gunner, camera, look, target, targetPos, state,
            quality, error, bloom, shots, pause, ready, ammo, suppression, suppressionPos,
            range, yawLimit, sweep, List.copyOf(observations));
    }

    private static void writeVec(FriendlyByteBuf buf, Vec3 value) {
        buf.writeDouble(value.x);
        buf.writeDouble(value.y);
        buf.writeDouble(value.z);
    }

    private static Vec3 readVec(FriendlyByteBuf buf) {
        return new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble());
    }

    private static void writeNullableUuid(FriendlyByteBuf buf, UUID value) {
        buf.writeBoolean(value != null);
        if (value != null) buf.writeUUID(value);
    }

    private static UUID readNullableUuid(FriendlyByteBuf buf) {
        return buf.readBoolean() ? buf.readUUID() : null;
    }

    private static void writeNullableVec(FriendlyByteBuf buf, Vec3 value) {
        buf.writeBoolean(value != null);
        if (value != null) writeVec(buf, value);
    }

    private static Vec3 readNullableVec(FriendlyByteBuf buf) {
        return buf.readBoolean() ? readVec(buf) : null;
    }

    public static void handle(VehicleCrewDebugPacket packet, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
            () -> () -> ClientVehicleCrewDebugData.INSTANCE.receive(packet)));
        ctx.get().setPacketHandled(true);
    }

    public int mode() { return mode; }
    public List<Entry> entries() { return entries; }
}
