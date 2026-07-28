package com.stevesarmy.network;

import com.stevesarmy.squad.FireTeam;
import com.stevesarmy.squad.FireTeamAssignment;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** Synchronizes the named fire team used for subsequently spawned soldiers. */
public class SetSelectedFireTeamPacket {
    private final FireTeam team;

    public SetSelectedFireTeamPacket(FireTeam team) {
        this.team = team;
    }

    public static void encode(SetSelectedFireTeamPacket message, FriendlyByteBuf buffer) {
        buffer.writeEnum(message.team);
    }

    public static SetSelectedFireTeamPacket decode(FriendlyByteBuf buffer) {
        return new SetSelectedFireTeamPacket(buffer.readEnum(FireTeam.class));
    }

    public static void handle(SetSelectedFireTeamPacket message, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null || !(player.level() instanceof ServerLevel serverLevel)) {
                return;
            }

            if (message.team != FireTeam.ALL) {
                FireTeamAssignment.get(serverLevel, player.getUUID()).setSelectedSpawnTeam(message.team);
            }
        });
        context.setPacketHandled(true);
    }
}
