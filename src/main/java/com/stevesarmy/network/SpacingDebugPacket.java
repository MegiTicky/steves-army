package com.stevesarmy.network;

import com.stevesarmy.client.SpacingDebugRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
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
        public final BlockPos rawTarget;
        public final BlockPos navigationTarget;
        public final BlockPos offset;
        public final int commandGeneration;

        public SpacingDebugEntry(UUID soldierUUID, BlockPos rawTarget, BlockPos navigationTarget,
                                 BlockPos offset, int commandGeneration) {
            this.soldierUUID = soldierUUID;
            this.rawTarget = rawTarget;
            this.navigationTarget = navigationTarget;
            this.offset = offset;
            this.commandGeneration = commandGeneration;
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
            BlockPos raw = buf.readBlockPos();
            BlockPos nav = buf.readBlockPos();
            boolean hasOffset = buf.readBoolean();
            BlockPos offset = hasOffset ? buf.readBlockPos() : null;
            int gen = buf.readInt();
            entries.add(new SpacingDebugEntry(uuid, raw, nav, offset, gen));
        }
    }

    public static void encode(SpacingDebugPacket msg, FriendlyByteBuf buf) {
        buf.writeBoolean(msg.enabled);
        buf.writeInt(msg.entries.size());
        for (SpacingDebugEntry entry : msg.entries) {
            buf.writeUUID(entry.soldierUUID);
            buf.writeBlockPos(entry.rawTarget);
            buf.writeBlockPos(entry.navigationTarget);
            buf.writeBoolean(entry.offset != null);
            if (entry.offset != null) {
                buf.writeBlockPos(entry.offset);
            }
            buf.writeInt(entry.commandGeneration);
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