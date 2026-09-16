package com.stevesarmy.network;

import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.client.ClientSuppressPingDebugData;
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
 * Per-soldier snapshot of the ping-suppress pipeline for the client debug
 * render: ping centre, cached aim points with live LOS validity, the current
 * shot lane, and a server-built status line.
 */
public class SuppressPingDebugPacket {
    private final boolean renderEnabled;
    private final UUID soldierId;
    private final Vec3 soldierPos;
    private final Vec3 pingPos;
    private final boolean heavy;
    private final int remainingTicks;
    private final int durationTicks;
    private final String statusLine;
    private final Vec3 currentTarget;
    private final Vec3 relocTargetPos;
    private final List<Vec3> aimPoints;
    private final List<Boolean> aimPointValid;
    private final String relocStatus;
    private final int relocFailures;
    private final String relocLastFailure;
    private final Vec3 searchOrigin;
    private final float searchRadius;
    private final List<Vec3> navPath;
    private final int blindTicks;
    private final int blindThreshold;

    public SuppressPingDebugPacket(boolean renderEnabled, UUID soldierId, Vec3 soldierPos,
                                   Vec3 pingPos, boolean heavy, int remainingTicks, int durationTicks,
                                   String statusLine, Vec3 currentTarget, Vec3 relocTargetPos,
                                   List<Vec3> aimPoints, List<Boolean> aimPointValid,
                                   String relocStatus, int relocFailures, String relocLastFailure,
                                   Vec3 searchOrigin, float searchRadius, List<Vec3> navPath,
                                   int blindTicks, int blindThreshold) {
        this.renderEnabled = renderEnabled;
        this.soldierId = soldierId;
        this.soldierPos = soldierPos;
        this.pingPos = pingPos;
        this.heavy = heavy;
        this.remainingTicks = remainingTicks;
        this.durationTicks = durationTicks;
        this.statusLine = statusLine;
        this.currentTarget = currentTarget;
        this.relocTargetPos = relocTargetPos;
        this.aimPoints = aimPoints;
        this.aimPointValid = aimPointValid;
        this.relocStatus = relocStatus;
        this.relocFailures = relocFailures;
        this.relocLastFailure = relocLastFailure;
        this.searchOrigin = searchOrigin;
        this.searchRadius = searchRadius;
        this.navPath = navPath;
        this.blindTicks = blindTicks;
        this.blindThreshold = blindThreshold;
    }

    public SuppressPingDebugPacket(FriendlyByteBuf buf) {
        this.renderEnabled = buf.readBoolean();
        this.soldierId = buf.readUUID();
        this.soldierPos = readVec(buf);
        this.pingPos = buf.readBoolean() ? readVec(buf) : null;
        this.heavy = buf.readBoolean();
        this.remainingTicks = buf.readVarInt();
        this.durationTicks = buf.readVarInt();
        this.statusLine = buf.readUtf();
        this.currentTarget = buf.readBoolean() ? readVec(buf) : null;
        this.relocTargetPos = buf.readBoolean() ? readVec(buf) : null;
        int count = buf.readVarInt();
        this.aimPoints = new ArrayList<>(count);
        this.aimPointValid = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            this.aimPoints.add(readVec(buf));
            this.aimPointValid.add(buf.readBoolean());
        }
        this.relocStatus = buf.readUtf();
        this.relocFailures = buf.readVarInt();
        this.relocLastFailure = buf.readUtf();
        this.searchOrigin = buf.readBoolean() ? readVec(buf) : null;
        this.searchRadius = buf.readFloat();
        int pathCount = buf.readVarInt();
        this.navPath = new ArrayList<>(pathCount);
        for (int i = 0; i < pathCount; i++) {
            this.navPath.add(readVec(buf));
        }
        this.blindTicks = buf.readVarInt();
        this.blindThreshold = buf.readVarInt();
    }

    private static Vec3 readVec(FriendlyByteBuf buf) {
        return new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble());
    }

    private static void writeVec(FriendlyByteBuf buf, Vec3 v) {
        buf.writeDouble(v.x);
        buf.writeDouble(v.y);
        buf.writeDouble(v.z);
    }

    public static void encode(SuppressPingDebugPacket msg, FriendlyByteBuf buf) {
        buf.writeBoolean(msg.renderEnabled);
        buf.writeUUID(msg.soldierId);
        writeVec(buf, msg.soldierPos);
        buf.writeBoolean(msg.pingPos != null);
        if (msg.pingPos != null) writeVec(buf, msg.pingPos);
        buf.writeBoolean(msg.heavy);
        buf.writeVarInt(msg.remainingTicks);
        buf.writeVarInt(msg.durationTicks);
        buf.writeUtf(msg.statusLine);
        buf.writeBoolean(msg.currentTarget != null);
        if (msg.currentTarget != null) writeVec(buf, msg.currentTarget);
        buf.writeBoolean(msg.relocTargetPos != null);
        if (msg.relocTargetPos != null) writeVec(buf, msg.relocTargetPos);
        buf.writeVarInt(msg.aimPoints.size());
        for (int i = 0; i < msg.aimPoints.size(); i++) {
            writeVec(buf, msg.aimPoints.get(i));
            buf.writeBoolean(msg.aimPointValid.get(i));
        }
        buf.writeUtf(msg.relocStatus);
        buf.writeVarInt(msg.relocFailures);
        buf.writeUtf(msg.relocLastFailure);
        buf.writeBoolean(msg.searchOrigin != null);
        if (msg.searchOrigin != null) writeVec(buf, msg.searchOrigin);
        buf.writeFloat(msg.searchRadius);
        buf.writeVarInt(msg.navPath.size());
        for (Vec3 node : msg.navPath) {
            writeVec(buf, node);
        }
        buf.writeVarInt(msg.blindTicks);
        buf.writeVarInt(msg.blindThreshold);
    }

    public static void handle(SuppressPingDebugPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> {
                ClientSuppressPingDebugData.INSTANCE.receive(msg);
                if (StevesArmyMod.LOGGER.isDebugEnabled()) {
                    StevesArmyMod.LOGGER.debug("[SuppressPingDebug] received for {}", msg.soldierId);
                }
            });
        });
        ctx.get().setPacketHandled(true);
    }

    public boolean renderEnabled() { return renderEnabled; }
    public UUID soldierId() { return soldierId; }
    public Vec3 soldierPos() { return soldierPos; }
    public Vec3 pingPos() { return pingPos; }
    public boolean heavy() { return heavy; }
    public int remainingTicks() { return remainingTicks; }
    public int durationTicks() { return durationTicks; }
    public String statusLine() { return statusLine; }
    public Vec3 currentTarget() { return currentTarget; }
    public Vec3 relocTargetPos() { return relocTargetPos; }
    public List<Vec3> aimPoints() { return aimPoints; }
    public List<Boolean> aimPointValid() { return aimPointValid; }
    public String relocStatus() { return relocStatus; }
    public int relocFailures() { return relocFailures; }
    public String relocLastFailure() { return relocLastFailure; }
    public Vec3 searchOrigin() { return searchOrigin; }
    public float searchRadius() { return searchRadius; }
    public List<Vec3> navPath() { return navPath; }
    public int blindTicks() { return blindTicks; }
    public int blindThreshold() { return blindThreshold; }
}
