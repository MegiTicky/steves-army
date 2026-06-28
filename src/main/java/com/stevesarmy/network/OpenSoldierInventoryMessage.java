package com.stevesarmy.network;

import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.inventory.SoldierInventoryMenuProvider;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkHooks;

import java.util.function.Supplier;

public class OpenSoldierInventoryMessage {
    private int soldierId;

    public OpenSoldierInventoryMessage() {
    }

    public OpenSoldierInventoryMessage(int soldierId) {
        this.soldierId = soldierId;
    }

    public static void encode(OpenSoldierInventoryMessage msg, FriendlyByteBuf buf) {
        buf.writeInt(msg.soldierId);
    }

    public static OpenSoldierInventoryMessage decode(FriendlyByteBuf buf) {
        return new OpenSoldierInventoryMessage(buf.readInt());
    }

    public static void handle(OpenSoldierInventoryMessage msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;

            Entity entity = player.level().getEntity(msg.soldierId);
            if (entity instanceof SoldierEntity soldier && soldier.isOwnedBy(player)) {
                NetworkHooks.openScreen(player, 
                    new SoldierInventoryMenuProvider(soldier),
                    buf -> buf.writeInt(msg.soldierId));
            }
        });
        ctx.get().setPacketHandled(true);
    }
}