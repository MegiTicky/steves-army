package com.stevesarmy.network;

import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.squad.FireTeamAssignment;
import com.stevesarmy.squad.OwnedSoldierRegistry;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

/** Permanently removes an owned soldier without treating dismissal as combat death. */
public class DismissSoldierPacket {
    private final UUID soldierId;

    public DismissSoldierPacket(UUID soldierId) {
        this.soldierId = soldierId;
    }

    public static void encode(DismissSoldierPacket message, FriendlyByteBuf buffer) {
        buffer.writeUUID(message.soldierId);
    }

    public static DismissSoldierPacket decode(FriendlyByteBuf buffer) {
        return new DismissSoldierPacket(buffer.readUUID());
    }

    public static void handle(DismissSoldierPacket message, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null || !(player.level() instanceof ServerLevel serverLevel)) {
                return;
            }

            OwnedSoldierRegistry registry = OwnedSoldierRegistry.get(player.getServer());
            OwnedSoldierRegistry.Entry entry = registry.get(message.soldierId);
            if (entry == null || !player.getUUID().equals(entry.ownerId())) {
                return;
            }

            FireTeamAssignment fireTeams = FireTeamAssignment.get(player.getServer().overworld(), player.getUUID());
            fireTeams.removeSoldier(message.soldierId);
            for (ServerLevel level : player.getServer().getAllLevels()) {
                Entity entity = level.getEntity(message.soldierId);
                if (entity instanceof SoldierEntity soldier && soldier.isOwnedBy(player)) {
                    soldier.discard();
                }
            }
            registry.dismiss(message.soldierId);
            NetworkHandler.sendTo(player, SquadStatusSyncPacket.createForPlayer(player));
        });
        context.setPacketHandled(true);
    }
}
