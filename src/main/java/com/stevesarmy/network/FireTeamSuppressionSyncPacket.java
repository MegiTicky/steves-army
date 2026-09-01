package com.stevesarmy.network;

import com.stevesarmy.client.ClientFireTeamSuppressionData;
import com.stevesarmy.squad.FireTeam;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

public class FireTeamSuppressionSyncPacket {
    private final List<Entry> entries;

    public FireTeamSuppressionSyncPacket(List<Entry> entries) {
        this.entries = List.copyOf(entries);
    }

    public FireTeamSuppressionSyncPacket(FriendlyByteBuf buf) {
        int count = buf.readVarInt();
        List<Entry> decoded = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            decoded.add(new Entry(buf.readEnum(FireTeam.class), buf.readFloat(), buf.readVarInt(), buf.readBlockPos()));
        }
        this.entries = List.copyOf(decoded);
    }

    public static void encode(FireTeamSuppressionSyncPacket packet, FriendlyByteBuf buf) {
        buf.writeVarInt(packet.entries.size());
        for (Entry e : packet.entries) {
            buf.writeEnum(e.fireTeam());
            buf.writeFloat(e.level());
            buf.writeVarInt(e.state());
            buf.writeBlockPos(e.centroid());
        }
    }

    public static void handle(FireTeamSuppressionSyncPacket packet, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> ClientFireTeamSuppressionData.INSTANCE.update(packet.entries));
        context.setPacketHandled(true);
    }

    public record Entry(FireTeam fireTeam, float level, int state, BlockPos centroid) {}
}
