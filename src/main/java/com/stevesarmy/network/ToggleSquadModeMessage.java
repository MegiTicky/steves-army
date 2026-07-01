package com.stevesarmy.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

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
            
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                "Use the ping wheel (Middle Mouse) to set squad modes: FOLLOW, HOLD, GO_TO, or THREAT_DIRECTION"
            ));
        });
        ctx.get().setPacketHandled(true);
    }
}