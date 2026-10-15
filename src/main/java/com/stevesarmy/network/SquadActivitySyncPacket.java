package com.stevesarmy.network;

import com.stevesarmy.client.ClientSquadActivityData;
import com.stevesarmy.squad.FireTeam;
import com.stevesarmy.squad.SquadActivityType;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

public class SquadActivitySyncPacket {
    private final List<ActivityEntry> entries;

    public SquadActivitySyncPacket(List<ActivityEntry> entries) {
        this.entries = List.copyOf(entries);
    }

    public SquadActivitySyncPacket(FriendlyByteBuf buf) {
        int count = buf.readVarInt();
        List<ActivityEntry> decoded = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            decoded.add(new ActivityEntry(
                buf.readEnum(FireTeam.class),
                buf.readEnum(SquadActivityType.class),
                buf.readBlockPos(),
                buf.readInt(),
                buf.readLong(),
                buf.readVarInt(),
                buf.readVarInt()
            ));
        }
        this.entries = List.copyOf(decoded);
    }

    public static void encode(SquadActivitySyncPacket packet, FriendlyByteBuf buf) {
        buf.writeVarInt(packet.entries.size());
        for (ActivityEntry entry : packet.entries) {
            buf.writeEnum(entry.fireTeam());
            buf.writeEnum(entry.type());
            buf.writeBlockPos(entry.objective());
            buf.writeInt(entry.dimension());
            buf.writeLong(entry.generation());
            buf.writeVarInt(entry.clearingStage());
            buf.writeVarInt(entry.regroupingCount());
        }
    }

    public static void handle(SquadActivitySyncPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> ClientSquadActivityData.INSTANCE.update(packet.entries));
        context.setPacketHandled(true);
    }

    /**
     * @param clearingStage ClearingCoordinator.ClearingStage ordinal for ATTACK
     *                      orders (-1 = not an attack order / no coordinator state).
     * @param regroupingCount Recipients still travelling to a cleared objective.
     */
    public record ActivityEntry(FireTeam fireTeam, SquadActivityType type, BlockPos objective,
                                int dimension, long generation, int clearingStage, int regroupingCount) {
    }
}
