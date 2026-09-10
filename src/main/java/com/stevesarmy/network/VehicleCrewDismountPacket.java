package com.stevesarmy.network;

import com.stevesarmy.combat.StationGunnerAI;
import com.stevesarmy.compat.VS2Compat;
import com.stevesarmy.entity.SoldierEntity;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

/** Crew UI "Dismount": releases the crew soldier's station claim and dismounts it. */
public class VehicleCrewDismountPacket {
    private final UUID soldierId;

    public VehicleCrewDismountPacket(UUID soldierId) {
        this.soldierId = soldierId;
    }

    public VehicleCrewDismountPacket(FriendlyByteBuf buf) {
        this.soldierId = buf.readUUID();
    }

    public static void encode(VehicleCrewDismountPacket msg, FriendlyByteBuf buf) {
        buf.writeUUID(msg.soldierId);
    }

    public static void handle(VehicleCrewDismountPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer sender = ctx.get().getSender();
            if (sender == null) {
                return;
            }
            SoldierEntity soldier = findLoaded(sender, msg.soldierId);
            if (soldier == null || !soldier.isOwnedBy(sender)
                || !com.stevesarmy.entity.SoldierRole.VEHICLE_CREW.equals(soldier.getRole())) {
                return;
            }
            boolean hadDuty = StationGunnerAI.soldierHasStation(soldier);
            StationGunnerAI.deactivateForSoldier(soldier);
            boolean wasMounted = VS2Compat.releaseTransport(soldier);
            if (hadDuty || wasMounted) {
                sender.displayClientMessage(
                    Component.translatable("transport.steves_army.feedback.crew_dismounted"), true);
            } else {
                sender.displayClientMessage(
                    Component.translatable("transport.steves_army.feedback.crew_not_mounted"), true);
            }
        });
        ctx.get().setPacketHandled(true);
    }

    private static SoldierEntity findLoaded(ServerPlayer player, UUID soldierId) {
        for (ServerLevel level : player.getServer().getAllLevels()) {
            Entity entity = level.getEntity(soldierId);
            if (entity instanceof SoldierEntity soldier) {
                return soldier;
            }
        }
        return null;
    }
}
