package com.stevesarmy.network;

import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.squad.SquadData;
import com.stevesarmy.squad.SquadManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

public class CQBToggleMessage {

    public CQBToggleMessage() {
    }

    public CQBToggleMessage(FriendlyByteBuf buf) {
    }

    public static void encode(CQBToggleMessage msg, FriendlyByteBuf buf) {
    }

    public static CQBToggleMessage decode(FriendlyByteBuf buf) {
        return new CQBToggleMessage();
    }

    public static void handle(CQBToggleMessage msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer sender = ctx.get().getSender();
            if (sender == null) return;

            ServerLevel level = sender.serverLevel();
            SquadManager squadManager = SquadManager.get(level);

            Optional<SquadData> squadOpt = squadManager.getSquadByLeader(sender.getUUID());
            if (squadOpt.isEmpty()) {
                sender.sendSystemMessage(Component.literal("No squad to toggle CQB mode."));
                return;
            }

            SquadData squad = squadOpt.get();
            boolean newCQB = !squad.isCQB();
            squadManager.setSquadCQB(squad.getSquadId(), newCQB);

            sender.sendSystemMessage(Component.literal("CQB Mode: " + (newCQB ? "ON" : "OFF")));

            List<SoldierEntity> soldiers = level.getEntitiesOfClass(
                SoldierEntity.class,
                sender.getBoundingBox().inflate(100),
                s -> s.isOwnedBy(sender)
            );
            for (SoldierEntity soldier : soldiers) {
                soldier.setCQB(newCQB);
            }

            StevesArmyMod.LOGGER.info("Player {} toggled CQB mode to {}", sender.getName().getString(), newCQB);
        });
        ctx.get().setPacketHandled(true);
    }
}