package com.stevesarmy.network;

import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.skin.SoldierSkinManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * C2S: apply a named custom skin (or reset to the default when the name is
 * empty) to a soldier. The server re-validates the name against its own skins
 * folder and the owner-or-creative access rule.
 */
public class SetSkinPacket {
    private final int entityId;
    private final String skin;

    public SetSkinPacket(int entityId, String skin) {
        this.entityId = entityId;
        this.skin = skin == null ? "" : skin;
    }

    public static void encode(SetSkinPacket msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.entityId);
        buf.writeUtf(msg.skin, 128);
    }

    public static SetSkinPacket decode(FriendlyByteBuf buf) {
        return new SetSkinPacket(buf.readVarInt(), buf.readUtf(128));
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;
            Entity entity = player.level().getEntity(this.entityId);
            if (!(entity instanceof SoldierEntity soldier) || !soldier.isAlive()) return;
            if (!soldier.isOwnedBy(player) && !player.getAbilities().instabuild) return;

            if (this.skin.isEmpty()) {
                soldier.setSkinRaw("");
                player.displayClientMessage(Component.literal("Skin reset to default"), true);
            } else if (SoldierSkinManager.isKnown(this.skin)) {
                soldier.setSkin(this.skin);
                player.displayClientMessage(Component.literal("Skin: " + this.skin), true);
            } else {
                player.displayClientMessage(Component.literal(
                    "Unknown skin '" + this.skin + "' (not in " + SoldierSkinManager.getSkinFolder() + ")"), true);
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
