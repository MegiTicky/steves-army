package com.stevesarmy.network;

import com.stevesarmy.client.ClientFofState;
import com.stevesarmy.squad.FofCategory;
import com.stevesarmy.squad.FofSettings;
import com.stevesarmy.squad.FofStance;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/** Full FoF override snapshot for one squad owner (S2C). Sent on login and after every change. */
public class FofSyncPacket {
    private final Map<UUID, FofStance> players;
    private final Map<String, FofStance> teams;
    private final Map<FofCategory, FofStance> categories;

    public FofSyncPacket(Map<UUID, FofStance> players, Map<String, FofStance> teams,
                         Map<FofCategory, FofStance> categories) {
        this.players = players;
        this.teams = teams;
        this.categories = categories;
    }

    public static FofSyncPacket createFor(MinecraftServer server, UUID ownerId) {
        FofSettings.OwnerFof fof = FofSettings.get(server).peek(ownerId);
        if (fof == null) return new FofSyncPacket(Map.of(), Map.of(), Map.of());
        return new FofSyncPacket(fof.playerView(), fof.teamView(), fof.categoryView());
    }

    public static void encode(FofSyncPacket msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.players.size());
        for (Map.Entry<UUID, FofStance> entry : msg.players.entrySet()) {
            buf.writeUUID(entry.getKey());
            buf.writeByte(entry.getValue().ordinal());
        }
        buf.writeVarInt(msg.teams.size());
        for (Map.Entry<String, FofStance> entry : msg.teams.entrySet()) {
            buf.writeUtf(entry.getKey());
            buf.writeByte(entry.getValue().ordinal());
        }
        buf.writeVarInt(msg.categories.size());
        for (Map.Entry<FofCategory, FofStance> entry : msg.categories.entrySet()) {
            buf.writeUtf(entry.getKey().getSerializedName());
            buf.writeByte(entry.getValue().ordinal());
        }
    }

    public static FofSyncPacket decode(FriendlyByteBuf buf) {
        Map<UUID, FofStance> players = new HashMap<>();
        int playerCount = buf.readVarInt();
        for (int i = 0; i < playerCount; i++) {
            UUID playerId = buf.readUUID();
            FofStance stance = FofStance.fromOrdinal(buf.readByte());
            if (stance != null) players.put(playerId, stance);
        }
        Map<String, FofStance> teams = new HashMap<>();
        int teamCount = buf.readVarInt();
        for (int i = 0; i < teamCount; i++) {
            String name = buf.readUtf();
            FofStance stance = FofStance.fromOrdinal(buf.readByte());
            if (stance != null) teams.put(name, stance);
        }
        Map<FofCategory, FofStance> categories = new HashMap<>();
        int categoryCount = buf.readVarInt();
        for (int i = 0; i < categoryCount; i++) {
            String categoryName = buf.readUtf();
            FofStance stance = FofStance.fromOrdinal(buf.readByte());
            FofCategory category = FofCategory.fromName(categoryName);
            if (stance != null && category != null) categories.put(category, stance);
        }
        return new FofSyncPacket(players, teams, categories);
    }

    public static void handle(FofSyncPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
            () -> () -> ClientFofState.INSTANCE.update(msg.players, msg.teams, msg.categories)));
        ctx.get().setPacketHandled(true);
    }
}
