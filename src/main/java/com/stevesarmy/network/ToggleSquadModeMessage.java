package com.stevesarmy.network;

import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.squad.SquadMode;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

public class ToggleSquadModeMessage {
    public ToggleSquadModeMessage() {}

    public static void encode(ToggleSquadModeMessage msg, FriendlyByteBuf buf) {}

    public static ToggleSquadModeMessage decode(FriendlyByteBuf buf) {
        return new ToggleSquadModeMessage();
    }

    public static void handle(ToggleSquadModeMessage msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;
            
            ServerLevel level = player.serverLevel();
            UUID playerId = player.getUUID();
            
            List<SoldierEntity> soldiers = level.getEntitiesOfClass(SoldierEntity.class, 
                player.getBoundingBox().inflate(50),
                soldier -> soldier.getOwnerUUID().map(id -> id.equals(playerId)).orElse(false));
            
            if (soldiers.isEmpty()) return;
            
            SquadMode currentMode = soldiers.get(0).getSquadMode();
            SquadMode newMode = currentMode == SquadMode.FOLLOW ? SquadMode.HOLD : SquadMode.FOLLOW;
            
            for (SoldierEntity soldier : soldiers) {
                soldier.setSquadMode(newMode);
            }
            
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                "Squad mode: " + newMode.name() + " (" + soldiers.size() + " soldiers)"
            ));
        });
        ctx.get().setPacketHandled(true);
    }
}