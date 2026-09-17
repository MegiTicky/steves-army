package com.stevesarmy.network;

import com.stevesarmy.client.ClientSidearmDebugData;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Owner-only debug snapshots for the sidearm fallback: per nearby soldier,
 * the fallback state machine, both guns with their ammo, cover/suppression/
 * engagement state, and the server-computed restore-blocker string.
 */
public final class SidearmDebugPacket {
    private static final int MAX_SOLDIERS = 48;

    public record Soldier(UUID soldierId, Vec3 pos, boolean fallbackActive, int restoreCooldown,
                          String status, String heldGunId, int heldAmmo, int heldMag, boolean reloading,
                          String sidearmGunId, int sidearmAmmo, String origMainGunId,
                          String coverState, String suppState, boolean engaged) {}

    private final boolean renderEnabled;
    private final List<Soldier> soldiers;

    public SidearmDebugPacket(boolean renderEnabled, List<Soldier> soldiers) {
        this.renderEnabled = renderEnabled;
        this.soldiers = List.copyOf(soldiers);
    }

    public SidearmDebugPacket(FriendlyByteBuf buf) {
        renderEnabled = buf.readBoolean();
        int soldierCount = Math.min(buf.readVarInt(), MAX_SOLDIERS);
        List<Soldier> decoded = new ArrayList<>(soldierCount);
        for (int i = 0; i < soldierCount; i++) {
            decoded.add(new Soldier(buf.readUUID(), readVec(buf), buf.readBoolean(), buf.readVarInt(),
                buf.readUtf(), buf.readUtf(), buf.readVarInt(), buf.readVarInt(), buf.readBoolean(),
                buf.readUtf(), buf.readVarInt(), buf.readUtf(),
                buf.readUtf(), buf.readUtf(), buf.readBoolean()));
        }
        soldiers = List.copyOf(decoded);
    }

    public static void encode(SidearmDebugPacket packet, FriendlyByteBuf buf) {
        buf.writeBoolean(packet.renderEnabled);
        buf.writeVarInt(Math.min(packet.soldiers.size(), MAX_SOLDIERS));
        for (int i = 0; i < packet.soldiers.size() && i < MAX_SOLDIERS; i++) {
            Soldier soldier = packet.soldiers.get(i);
            buf.writeUUID(soldier.soldierId());
            writeVec(buf, soldier.pos());
            buf.writeBoolean(soldier.fallbackActive());
            buf.writeVarInt(soldier.restoreCooldown());
            buf.writeUtf(soldier.status() == null ? "idle" : soldier.status());
            buf.writeUtf(soldier.heldGunId() == null ? "" : soldier.heldGunId());
            buf.writeVarInt(soldier.heldAmmo());
            buf.writeVarInt(soldier.heldMag());
            buf.writeBoolean(soldier.reloading());
            buf.writeUtf(soldier.sidearmGunId() == null ? "" : soldier.sidearmGunId());
            buf.writeVarInt(soldier.sidearmAmmo());
            buf.writeUtf(soldier.origMainGunId() == null ? "" : soldier.origMainGunId());
            buf.writeUtf(soldier.coverState() == null ? "" : soldier.coverState());
            buf.writeUtf(soldier.suppState() == null ? "" : soldier.suppState());
            buf.writeBoolean(soldier.engaged());
        }
    }

    public static void handle(SidearmDebugPacket packet, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
            () -> () -> ClientSidearmDebugData.INSTANCE.receive(packet)));
        ctx.get().setPacketHandled(true);
    }

    public boolean renderEnabled() { return renderEnabled; }
    public List<Soldier> soldiers() { return soldiers; }

    private static void writeVec(FriendlyByteBuf buf, Vec3 value) {
        buf.writeDouble(value.x);
        buf.writeDouble(value.y);
        buf.writeDouble(value.z);
    }

    private static Vec3 readVec(FriendlyByteBuf buf) {
        return new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble());
    }
}
