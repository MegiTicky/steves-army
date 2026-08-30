package com.stevesarmy.network;

import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.entity.SoldierRole;
import com.stevesarmy.entity.SoldierRoleHandler;
import com.stevesarmy.squad.OwnedSoldierRegistry;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

public class SetSoldierRoleByUUIDPacket {
    private final UUID soldierId;
    private final int roleOrdinal;

    public SetSoldierRoleByUUIDPacket(UUID soldierId, SoldierRole role) {
        this.soldierId = soldierId;
        this.roleOrdinal = role.ordinal();
    }

    private SetSoldierRoleByUUIDPacket(UUID soldierId, int roleOrdinal) {
        this.soldierId = soldierId;
        this.roleOrdinal = roleOrdinal;
    }

    public static void encode(SetSoldierRoleByUUIDPacket message, FriendlyByteBuf buffer) {
        buffer.writeUUID(message.soldierId);
        buffer.writeVarInt(message.roleOrdinal);
    }

    public static SetSoldierRoleByUUIDPacket decode(FriendlyByteBuf buffer) {
        return new SetSoldierRoleByUUIDPacket(buffer.readUUID(), buffer.readVarInt());
    }

    public static void handle(SetSoldierRoleByUUIDPacket message, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null) return;

            SoldierRole[] roles = SoldierRole.values();
            if (message.roleOrdinal < 0 || message.roleOrdinal >= roles.length) return;
            OwnedSoldierRegistry registry = OwnedSoldierRegistry.get(player.getServer());
            OwnedSoldierRegistry.Entry entry = registry.get(message.soldierId);
            if (entry == null || !player.getUUID().equals(entry.ownerId())) return;

            SoldierEntity soldier = findLoaded(player, message.soldierId);
            if (soldier != null) {
                if (!(soldier.isOwnedBy(player) || player.getAbilities().instabuild)) return;
                SoldierRoleHandler.convertSoldier(soldier, roles[message.roleOrdinal]);
            } else {
                registry.setPendingRole(message.soldierId, roles[message.roleOrdinal]);
            }
            NetworkHandler.sendTo(player, SquadStatusSyncPacket.createForPlayer(player));
        });
        context.setPacketHandled(true);
    }

    private static SoldierEntity findLoaded(ServerPlayer player, UUID soldierId) {
        for (ServerLevel level : player.getServer().getAllLevels()) {
            Entity entity = level.getEntity(soldierId);
            if (entity instanceof SoldierEntity soldier) return soldier;
        }
        return null;
    }
}
