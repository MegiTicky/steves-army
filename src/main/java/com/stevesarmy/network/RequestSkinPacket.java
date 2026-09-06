package com.stevesarmy.network;

import com.stevesarmy.skin.SoldierSkinManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.List;
import java.util.function.Supplier;

/**
 * C2S: the client hit a synced skin name it cannot resolve locally (typically
 * a skin added to the server folder after the player logged in). The server
 * replies with a single-entry {@link SyncSoldierSkinsPacket}, or nothing when
 * the name is unknown — the client never re-requests a name within a session.
 */
public class RequestSkinPacket {
    private final String name;

    public RequestSkinPacket(String name) {
        this.name = name == null ? "" : name;
    }

    public static void encode(RequestSkinPacket msg, FriendlyByteBuf buf) {
        buf.writeUtf(msg.name, 128);
    }

    public static RequestSkinPacket decode(FriendlyByteBuf buf) {
        return new RequestSkinPacket(buf.readUtf(128));
    }

    public static void handle(RequestSkinPacket msg, Supplier<NetworkEvent.Context> context) {
        context.get().enqueueWork(() -> {
            ServerPlayer player = context.get().getSender();
            if (player == null || msg.name.isEmpty()) return;
            byte[] data = SoldierSkinManager.readSkinForTransfer(msg.name);
            if (data != null) {
                NetworkHandler.sendTo(player, new SyncSoldierSkinsPacket(
                    List.of(new SyncSoldierSkinsPacket.SkinData(msg.name, data))));
            }
        });
        context.get().setPacketHandled(true);
    }
}
