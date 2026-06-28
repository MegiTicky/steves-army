package com.stevesarmy.network;

import com.stevesarmy.combat.GunIntegration;
import com.stevesarmy.entity.SoldierEntity;
import net.minecraft.ChatFormatting;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.List;
import java.util.function.Supplier;

public class DebugMessage {
    public static void encode(DebugMessage msg, FriendlyByteBuf buf) {
    }

    public static DebugMessage decode(FriendlyByteBuf buf) {
        return new DebugMessage();
    }

    public static void handle(DebugMessage msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;

            player.sendSystemMessage(Component.literal("=== Steve's Army Debug ===").withStyle(ChatFormatting.YELLOW));

            ServerLevel level = (ServerLevel) player.level();
            List<SoldierEntity> soldiers = level.getEntitiesOfClass(
                SoldierEntity.class,
                player.getBoundingBox().inflate(100),
                s -> s.isOwnedBy(player)
            );

            player.sendSystemMessage(Component.literal("Soldiers nearby: " + soldiers.size()));
            player.sendSystemMessage(Component.literal("TaCZ loaded: " + GunIntegration.isTaczLoaded()).withStyle(ChatFormatting.AQUA));

            for (SoldierEntity soldier : soldiers) {
                String mode = soldier.getSquadMode().name();
                double dist = soldier.distanceTo(player);
                String targetInfo = soldier.getTarget() != null ? 
                    "Target: " + soldier.getTarget().getName().getString() + " (" + (int)soldier.distanceTo(soldier.getTarget()) + "m)" : 
                    "No target";
                boolean hasGun = GunIntegration.hasGun(soldier);
                String gunInfo = hasGun ? "Gun equipped" : "No gun";

                player.sendSystemMessage(Component.literal(
                    String.format("  [%s] %.1fm | %s | %s", mode, dist, targetInfo, gunInfo)
                ));
            }
        });
        ctx.get().setPacketHandled(true);
    }
}