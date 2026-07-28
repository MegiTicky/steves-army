package com.stevesarmy.network;

import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.squad.FireTeamAssignment;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** Permanently removes an owned soldier without treating dismissal as combat death. */
public class DismissSoldierPacket {
    private final int soldierId;

    public DismissSoldierPacket(int soldierId) {
        this.soldierId = soldierId;
    }

    public static void encode(DismissSoldierPacket message, FriendlyByteBuf buffer) {
        buffer.writeInt(message.soldierId);
    }

    public static DismissSoldierPacket decode(FriendlyByteBuf buffer) {
        return new DismissSoldierPacket(buffer.readInt());
    }

    public static void handle(DismissSoldierPacket message, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null || !(player.level() instanceof ServerLevel serverLevel)) {
                return;
            }

            Entity entity = serverLevel.getEntity(message.soldierId);
            if (!(entity instanceof SoldierEntity soldier) || !soldier.isOwnedBy(player)) {
                return;
            }

            FireTeamAssignment fireTeams = FireTeamAssignment.get(serverLevel, player.getUUID());
            fireTeams.removeSoldier(soldier.getUUID());
            soldier.discard();
            NetworkHandler.sendTo(player, SquadStatusSyncPacket.createForPlayer(player));
        });
        context.setPacketHandled(true);
    }
}
