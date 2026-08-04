package com.stevesarmy.network;

import com.stevesarmy.combat.RecallRequestManager;
import com.stevesarmy.squad.OwnedSoldierRegistry;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

public class RecallPacket {
    private final UUID soldierId;

    public RecallPacket(UUID soldierId) {
        this.soldierId = soldierId;
    }

    public static void encode(RecallPacket msg, FriendlyByteBuf buf) {
        buf.writeUUID(msg.soldierId);
    }

    public static RecallPacket decode(FriendlyByteBuf buf) {
        return new RecallPacket(buf.readUUID());
    }

    public static void handle(RecallPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;
            if (!OwnedSoldierRegistry.get(player.getServer()).getOwned(player.getUUID()).stream()
                .anyMatch(entry -> entry.soldierId().equals(msg.soldierId))) return;
            RecallRequestManager.start(player, msg.soldierId);
        });
        ctx.get().setPacketHandled(true);
    }
}
