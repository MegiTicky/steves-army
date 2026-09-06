package com.stevesarmy.network;

import com.stevesarmy.client.SoldierSkinLoader;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * S2C: pushes skin PNG bytes from the server's skins folder to the client so
 * dedicated-server players can render skins they do not have locally. Sent
 * in full on login and per-skin as a reply to {@link RequestSkinPacket}.
 */
public class SyncSoldierSkinsPacket {

    /** Hard cap on entries accepted on decode to keep a corrupt stream from ballooning. */
    private static final int MAX_ENTRIES = 4096;

    private final List<SkinData> entries;

    public SyncSoldierSkinsPacket(List<SkinData> entries) {
        this.entries = List.copyOf(entries);
    }

    public record SkinData(String name, byte[] data) {}

    public static void encode(SyncSoldierSkinsPacket msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.entries.size());
        for (SkinData entry : msg.entries) {
            buf.writeUtf(entry.name(), 128);
            buf.writeByteArray(entry.data());
        }
    }

    public static SyncSoldierSkinsPacket decode(FriendlyByteBuf buf) {
        int count = Math.min(buf.readVarInt(), MAX_ENTRIES);
        List<SkinData> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            entries.add(new SkinData(buf.readUtf(128), buf.readByteArray()));
        }
        return new SyncSoldierSkinsPacket(entries);
    }

    public static void handle(SyncSoldierSkinsPacket msg, Supplier<NetworkEvent.Context> context) {
        context.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
            () -> () -> SoldierSkinLoader.receiveServerSkins(msg.entries)));
        context.get().setPacketHandled(true);
    }
}
