package com.stevesarmy.network;

import com.stevesarmy.client.ClientMovementSpeedDebugData;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

public record MovementSpeedDebugPacket(boolean enabled, List<Entry> entries) {
    private static final int MAX_ENTRIES = 32;

    public record Entry(int entityId, UUID soldierId, String name, Vec3 position, Vec3 displacement,
                        double measuredSpeed, double averageSpeed, double peakSpeed,
                        double requestedModifier, double targetSpeed, double appliedSpeed,
                        double attributeSpeed, double incomingSpeed, double spentBudget,
                        boolean impulseBypass, String limitingReason, String movementOwner,
                        String coverState, String posture, boolean onGround, boolean collision) {}

    public MovementSpeedDebugPacket {
        if (entries.size() > MAX_ENTRIES) throw new IllegalArgumentException("Too many movement debug entries");
        entries = List.copyOf(entries);
    }

    public MovementSpeedDebugPacket(FriendlyByteBuf buf) {
        this(buf.readBoolean(), readEntries(buf));
    }

    private static List<Entry> readEntries(FriendlyByteBuf buf) {
        int count = buf.readVarInt();
        if (count < 0 || count > MAX_ENTRIES) throw new IllegalArgumentException("Invalid movement debug entry count: " + count);
        java.util.ArrayList<Entry> entries = new java.util.ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            entries.add(new Entry(buf.readVarInt(), buf.readUUID(), buf.readUtf(128), readVec(buf), readVec(buf),
                buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readDouble(),
                buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readBoolean(),
                buf.readUtf(128), buf.readUtf(256), buf.readUtf(64), buf.readUtf(64),
                buf.readBoolean(), buf.readBoolean()));
        }
        return entries;
    }

    private static Vec3 readVec(FriendlyByteBuf buf) {
        return new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble());
    }

    private static void writeVec(FriendlyByteBuf buf, Vec3 vec) {
        buf.writeDouble(vec.x);
        buf.writeDouble(vec.y);
        buf.writeDouble(vec.z);
    }

    public static void encode(MovementSpeedDebugPacket packet, FriendlyByteBuf buf) {
        buf.writeBoolean(packet.enabled);
        int count = Math.min(packet.entries.size(), MAX_ENTRIES);
        buf.writeVarInt(count);
        for (int i = 0; i < count; i++) {
            Entry entry = packet.entries.get(i);
            buf.writeVarInt(entry.entityId);
            buf.writeUUID(entry.soldierId);
            buf.writeUtf(bounded(entry.name, 128), 128);
            writeVec(buf, entry.position);
            writeVec(buf, entry.displacement);
            buf.writeDouble(entry.measuredSpeed);
            buf.writeDouble(entry.averageSpeed);
            buf.writeDouble(entry.peakSpeed);
            buf.writeDouble(entry.requestedModifier);
            buf.writeDouble(entry.targetSpeed);
            buf.writeDouble(entry.appliedSpeed);
            buf.writeDouble(entry.attributeSpeed);
            buf.writeDouble(entry.incomingSpeed);
            buf.writeDouble(entry.spentBudget);
            buf.writeBoolean(entry.impulseBypass);
            buf.writeUtf(bounded(entry.limitingReason, 128), 128);
            buf.writeUtf(bounded(entry.movementOwner, 256), 256);
            buf.writeUtf(bounded(entry.coverState, 64), 64);
            buf.writeUtf(bounded(entry.posture, 64), 64);
            buf.writeBoolean(entry.onGround);
            buf.writeBoolean(entry.collision);
        }
    }

    private static String bounded(String value, int maxChars) {
        if (value == null) return "";
        return value.length() <= maxChars ? value : value.substring(0, maxChars);
    }

    public static void handle(MovementSpeedDebugPacket packet, Supplier<NetworkEvent.Context> context) {
        context.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
            () -> () -> ClientMovementSpeedDebugData.INSTANCE.receive(packet)));
        context.get().setPacketHandled(true);
    }
}
