package com.stevesarmy.network;

import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.squad.SquadFormation;
import com.stevesarmy.squad.SquadManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

public class FormationMessage {
    private final SquadFormation formation;

    public FormationMessage(SquadFormation formation) {
        this.formation = formation;
    }

    public FormationMessage(FriendlyByteBuf buf) {
        this.formation = SquadFormation.values()[buf.readInt()];
    }

    public static void encode(FormationMessage msg, FriendlyByteBuf buf) {
        buf.writeInt(msg.formation.ordinal());
    }

    public SquadFormation getFormation() { return formation; }

    public static void handle(FormationMessage msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer sender = ctx.get().getSender();
            if (sender == null) return;

            ServerLevel level = sender.serverLevel();
            SquadFormation formation = msg.getFormation();

            // Find the player's squad
            SquadManager squadManager = SquadManager.get(level);
            squadManager.setSquadFormationByLeader(sender.getUUID(), formation);
            squadManager.setDirty();

            // Send chat feedback to the player
            sender.sendSystemMessage(
                Component.literal("Squad formation set to " + formation.getDisplayName())
            );

            StevesArmyMod.LOGGER.info("Player {} set squad formation to {}", sender.getName().getString(), formation.getDisplayName());

            // Update formation on all owned soldiers (for future use by cover system)
            List<SoldierEntity> soldiers = level.getEntitiesOfClass(
                SoldierEntity.class,
                sender.getBoundingBox().inflate(100),
                s -> s.isOwnedBy(sender)
            );
            for (SoldierEntity soldier : soldiers) {
                soldier.setSquadFormation(formation);
            }
        });
        ctx.get().setPacketHandled(true);
    }
}