package com.stevesarmy.network;

import com.stevesarmy.client.ClientAttackDebugData;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

public class AttackDebugPacket {
    private final boolean enabled;
    private final List<Entry> entries;

    public record Entry(
        UUID soldierUUID,
        String fireTeamName,
        float fireteamLevel,
        int fireteamState,
        int attackPhase,
        float dwellFraction,
        float suppressionLevel,
        boolean individualSuppressed,
        boolean recovered,
        boolean fireteamPinned,
        boolean heavyHold,
        boolean safetyPeekDone,
        boolean peeking,
        boolean hasTarget,
        Vec3 position,
        boolean attackHasPeeked,
        boolean dwellMet,
        boolean peekCompleted,
        boolean canAdvance,
        float dwellElapsedMs,
        float requiredDwellMs,
        float suppressionDwellMult,
        float groupCohesionMult,
        Vec3 centroidPos,
        int coverState,
        boolean coverSearchPending,
        boolean fallbackAdvanceActive,
        long phaseAgeMs,
        long lastAdvanceTriggerAgeMs
    ) {}

    public AttackDebugPacket(boolean enabled, List<Entry> entries) {
        this.enabled = enabled;
        this.entries = entries;
    }

    public AttackDebugPacket(FriendlyByteBuf buf) {
        this.enabled = buf.readBoolean();
        int count = buf.readInt();
        this.entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            UUID uuid = buf.readUUID();
            String ftName = buf.readUtf(16);
            float ftLevel = buf.readFloat();
            int ftState = buf.readVarInt();
            int phase = buf.readVarInt();
            float dwellFrac = buf.readFloat();
            float suppLevel = buf.readFloat();
            boolean indSupp = buf.readBoolean();
            boolean recov = buf.readBoolean();
            boolean ftPinned = buf.readBoolean();
            boolean hvHold = buf.readBoolean();
            boolean safePeek = buf.readBoolean();
            boolean peek = buf.readBoolean();
            boolean hasTgt = buf.readBoolean();
            double px = buf.readDouble();
            double py = buf.readDouble();
            double pz = buf.readDouble();
            boolean atkPeek = buf.readBoolean();
            boolean dwMet = buf.readBoolean();
            boolean peekComp = buf.readBoolean();
            boolean canAdv = buf.readBoolean();
            float dwellEl = buf.readFloat();
            float reqDwell = buf.readFloat();
            float suppDwellMult = buf.readFloat();
            float cohMult = buf.readFloat();
            double cx = buf.readDouble();
            double cy = buf.readDouble();
            double cz = buf.readDouble();
            Vec3 centroid = (cx == 0 && cy == 0 && cz == 0) ? null : new Vec3(cx, cy, cz);
            int coverSt = buf.readVarInt();
            boolean searchPend = buf.readBoolean();
            boolean fbActive = buf.readBoolean();
            long phaseAge = buf.readLong();
            long lastAdvAge = buf.readLong();
            entries.add(new Entry(uuid, ftName, ftLevel, ftState, phase, dwellFrac,
                suppLevel, indSupp, recov, ftPinned, hvHold, safePeek, peek, hasTgt,
                new Vec3(px, py, pz), atkPeek, dwMet, peekComp, canAdv, dwellEl, reqDwell, suppDwellMult, cohMult, centroid,
                coverSt, searchPend, fbActive, phaseAge, lastAdvAge));
        }
    }

    public static void encode(AttackDebugPacket msg, FriendlyByteBuf buf) {
        buf.writeBoolean(msg.enabled);
        buf.writeInt(msg.entries.size());
        for (Entry e : msg.entries) {
            buf.writeUUID(e.soldierUUID());
            buf.writeUtf(e.fireTeamName(), 16);
            buf.writeFloat(e.fireteamLevel());
            buf.writeVarInt(e.fireteamState());
            buf.writeVarInt(e.attackPhase());
            buf.writeFloat(e.dwellFraction());
            buf.writeFloat(e.suppressionLevel());
            buf.writeBoolean(e.individualSuppressed());
            buf.writeBoolean(e.recovered());
            buf.writeBoolean(e.fireteamPinned());
            buf.writeBoolean(e.heavyHold());
            buf.writeBoolean(e.safetyPeekDone());
            buf.writeBoolean(e.peeking());
            buf.writeBoolean(e.hasTarget());
            buf.writeDouble(e.position().x);
            buf.writeDouble(e.position().y);
            buf.writeDouble(e.position().z);
            buf.writeBoolean(e.attackHasPeeked());
            buf.writeBoolean(e.dwellMet());
            buf.writeBoolean(e.peekCompleted());
            buf.writeBoolean(e.canAdvance());
            buf.writeFloat(e.dwellElapsedMs());
            buf.writeFloat(e.requiredDwellMs());
            buf.writeFloat(e.suppressionDwellMult());
            buf.writeFloat(e.groupCohesionMult());
            Vec3 c = e.centroidPos();
            buf.writeDouble(c != null ? c.x : 0);
            buf.writeDouble(c != null ? c.y : 0);
            buf.writeDouble(c != null ? c.z : 0);
            buf.writeVarInt(e.coverState());
            buf.writeBoolean(e.coverSearchPending());
            buf.writeBoolean(e.fallbackAdvanceActive());
            buf.writeLong(e.phaseAgeMs());
            buf.writeLong(e.lastAdvanceTriggerAgeMs());
        }
    }

    public static void handle(AttackDebugPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                ClientAttackDebugData.INSTANCE.receivePacket(msg));
        });
        ctx.get().setPacketHandled(true);
    }

    public boolean isEnabled() { return enabled; }
    public List<Entry> getEntries() { return entries; }
}
