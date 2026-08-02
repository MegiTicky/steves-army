package com.stevesarmy.network;

import com.stevesarmy.client.EnemyContactOverlay;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

public class EnemyContactSyncPacket {
    private final UUID threatId;
    private final boolean removed;
    private final Vec3 headPosition;
    private final int teamColor;
    private final boolean visible;

    private EnemyContactSyncPacket(UUID threatId, boolean removed, Vec3 headPosition, int teamColor, boolean visible) {
        this.threatId = threatId;
        this.removed = removed;
        this.headPosition = headPosition;
        this.teamColor = teamColor;
        this.visible = visible;
    }

    public EnemyContactSyncPacket(FriendlyByteBuf buf) {
        threatId = buf.readUUID();
        removed = buf.readBoolean();
        if (removed) {
            headPosition = Vec3.ZERO;
            teamColor = 0;
            visible = false;
            return;
        }

        headPosition = new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble());
        teamColor = buf.readInt();
        visible = buf.readBoolean();
    }

    public static EnemyContactSyncPacket upsert(UUID threatId, Vec3 headPosition, int teamColor, boolean visible) {
        return new EnemyContactSyncPacket(threatId, false, headPosition, teamColor, visible);
    }

    public static EnemyContactSyncPacket remove(UUID threatId) {
        return new EnemyContactSyncPacket(threatId, true, Vec3.ZERO, 0, false);
    }

    public static void encode(EnemyContactSyncPacket message, FriendlyByteBuf buf) {
        buf.writeUUID(message.threatId);
        buf.writeBoolean(message.removed);
        if (message.removed) {
            return;
        }

        buf.writeDouble(message.headPosition.x);
        buf.writeDouble(message.headPosition.y);
        buf.writeDouble(message.headPosition.z);
        buf.writeInt(message.teamColor);
        buf.writeBoolean(message.visible);
    }

    public static void handle(EnemyContactSyncPacket message, Supplier<NetworkEvent.Context> context) {
        context.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
            () -> () -> EnemyContactOverlay.receive(message)));
        context.get().setPacketHandled(true);
    }

    public UUID getThreatId() {
        return threatId;
    }

    public boolean isRemoved() {
        return removed;
    }

    public Vec3 getHeadPosition() {
        return headPosition;
    }

    public int getTeamColor() {
        return teamColor;
    }

    public boolean isVisible() {
        return visible;
    }
}
