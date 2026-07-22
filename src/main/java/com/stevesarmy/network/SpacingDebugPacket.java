package com.stevesarmy.network;

import com.stevesarmy.client.SpacingDebugRenderer;
import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.squad.SquadLaneAssignment;
import com.stevesarmy.squad.SquadLaneAssignment;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

public class SpacingDebugPacket {
    private final boolean enabled;
    private final List<SpacingDebugEntry> entries;

    public static class SpacingDebugEntry {
        public final UUID soldierUUID;
        public final boolean valid;
        public final BlockPos rawTarget;
        public final BlockPos navigationTarget;
        public final BlockPos offset;
        public final int commandGeneration;
        public final int laneIndex;
        public final int totalLanes;
        public final float forwardX;
        public final float forwardZ;
        public final float perpX;
        public final float perpZ;

        public SpacingDebugEntry(UUID soldierUUID, boolean valid, BlockPos rawTarget,
                                 BlockPos navigationTarget, BlockPos offset, int commandGeneration,
                                 int laneIndex, int totalLanes,
                                 float forwardX, float forwardZ, float perpX, float perpZ) {
            this.soldierUUID = soldierUUID;
            this.valid = valid;
            this.rawTarget = rawTarget;
            this.navigationTarget = navigationTarget;
            this.offset = offset;
            this.commandGeneration = commandGeneration;
            this.laneIndex = laneIndex;
            this.totalLanes = totalLanes;
            this.forwardX = forwardX;
            this.forwardZ = forwardZ;
            this.perpX = perpX;
            this.perpZ = perpZ;
        }

        public static SpacingDebugEntry fromAssignment(SoldierEntity soldier, SquadLaneAssignment assignment) {
            SquadLaneAssignment.LaneSlot slot = assignment.getSlot(soldier.getUUID());
            BlockPos rawTarget = assignment.getRawTarget();
            BlockPos navTarget = assignment.getLaneTarget(soldier.getUUID(), soldier.position());
            BlockPos offset = new BlockPos(
                (int) Math.round(assignment.getPerpendicular().x * (slot != null ? slot.laneOffset : 0)),
                0,
                (int) Math.round(assignment.getPerpendicular().z * (slot != null ? slot.laneOffset : 0)));
            return new SpacingDebugEntry(
                soldier.getUUID(), true,
                rawTarget, navTarget, offset,
                soldier.getPingMoveGeneration(),
                slot != null ? slot.laneIndex : 0,
                slot != null ? slot.totalLanes : 0,
                (float) assignment.getForward().x, (float) assignment.getForward().z,
                (float) assignment.getPerpendicular().x, (float) assignment.getPerpendicular().z);
        }
    }

    public SpacingDebugPacket(boolean enabled, List<SpacingDebugEntry> entries) {
        this.enabled = enabled;
        this.entries = entries;
    }

    public SpacingDebugPacket(FriendlyByteBuf buf) {
        this.enabled = buf.readBoolean();
        int count = buf.readInt();
        this.entries = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            UUID uuid = buf.readUUID();
            boolean valid = buf.readBoolean();
            BlockPos raw = valid ? buf.readBlockPos() : BlockPos.ZERO;
            BlockPos nav = valid ? buf.readBlockPos() : BlockPos.ZERO;
            boolean hasOffset = valid && buf.readBoolean();
            BlockPos offset = hasOffset ? buf.readBlockPos() : null;
            int gen = valid ? buf.readInt() : 0;
            int laneIdx = valid ? buf.readVarInt() : 0;
            int totalLanes = valid ? buf.readVarInt() : 0;
            float fwdX = valid ? buf.readFloat() : 0;
            float fwdZ = valid ? buf.readFloat() : 0;
            float perpX = valid ? buf.readFloat() : 0;
            float perpZ = valid ? buf.readFloat() : 0;
            entries.add(new SpacingDebugEntry(uuid, valid, raw, nav, offset, gen, laneIdx, totalLanes, fwdX, fwdZ, perpX, perpZ));
        }
    }

    public static void encode(SpacingDebugPacket msg, FriendlyByteBuf buf) {
        buf.writeBoolean(msg.enabled);
        buf.writeInt(msg.entries.size());
        for (SpacingDebugEntry entry : msg.entries) {
            buf.writeUUID(entry.soldierUUID);
            buf.writeBoolean(entry.valid);
            if (entry.valid) {
                buf.writeBlockPos(entry.rawTarget);
                buf.writeBlockPos(entry.navigationTarget);
                buf.writeBoolean(entry.offset != null);
                if (entry.offset != null) {
                    buf.writeBlockPos(entry.offset);
                }
                buf.writeInt(entry.commandGeneration);
                buf.writeVarInt(entry.laneIndex);
                buf.writeVarInt(entry.totalLanes);
                buf.writeFloat(entry.forwardX);
                buf.writeFloat(entry.forwardZ);
                buf.writeFloat(entry.perpX);
                buf.writeFloat(entry.perpZ);
            }
        }
    }

    public static void handle(SpacingDebugPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                SpacingDebugRenderer.receivePacket(msg)
            );
        });
        ctx.get().setPacketHandled(true);
    }

    public boolean isEnabled() { return enabled; }
    public List<SpacingDebugEntry> getEntries() { return entries; }
}