package com.stevesarmy.network;

import com.stevesarmy.client.ClientFofState;
import com.stevesarmy.squad.FofCategory;
import com.stevesarmy.squad.FofSettings;
import com.stevesarmy.squad.FofRelations;
import com.stevesarmy.squad.FofStance;
import com.stevesarmy.squad.OwnerScoreboardIdentity;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.Set;
import java.util.HashSet;
import java.util.function.Supplier;

/** Full FoF override snapshot for one squad owner (S2C). Sent on login and after every change. */
public class FofSyncPacket {
    private final Map<UUID, FofStance> players;
    private final Map<String, FofStance> teams;
    private final Map<FofCategory, FofStance> categories;
    private final Map<UUID, ClientFofState.Effective> effective;
    private final Map<UUID, ClientFofState.Pending> pending;

    public FofSyncPacket(Map<UUID, FofStance> players, Map<String, FofStance> teams,
                         Map<FofCategory, FofStance> categories,
                         Map<UUID, ClientFofState.Effective> effective,
                         Map<UUID, ClientFofState.Pending> pending) {
        this.players = players;
        this.teams = teams;
        this.categories = categories;
        this.effective = effective;
        this.pending = pending;
    }

    public static FofSyncPacket createFor(MinecraftServer server, UUID ownerId) {
        FofSettings.OwnerFof fof = FofSettings.get(server).peek(ownerId);
        FofRelations relations = FofRelations.get(server);
        String ownerTeam = teamName(server, ownerId);
        Set<UUID> candidates = new HashSet<>(relations.playerRelations(ownerId).keySet());
        relations.pendingFor(ownerId).forEach(change -> candidates.add(
            change.requester().equals(ownerId) ? change.other() : change.requester()));
        server.getPlayerList().getPlayers().forEach(player -> candidates.add(player.getUUID()));
        candidates.remove(ownerId);
        Map<UUID, ClientFofState.Effective> effective = new HashMap<>();
        for (UUID otherId : candidates) {
            var other = server.getPlayerList().getPlayer(otherId);
            String team = teamName(server, otherId);
            String name = playerName(server, otherId);
            FofRelations.Resolution resolution = relations.resolve(ownerId, ownerTeam, otherId,
                team, server.overworld().getGameTime());
            effective.put(otherId, new ClientFofState.Effective(resolution.stance(), resolution.source(),
                name == null ? otherId.toString().substring(0, 8) : name, team, other != null));
        }
        Map<UUID, ClientFofState.Pending> pending = new HashMap<>();
        for (FofRelations.PendingChange change : relations.pendingFor(ownerId)) {
            UUID other = change.requester().equals(ownerId) ? change.other() : change.requester();
            pending.put(other, new ClientFofState.Pending(change.requester(), change.desired(), change.acceptedByOther()));
        }
        return new FofSyncPacket(relations.playerRelations(ownerId),
            ownerTeam == null ? Map.of() : relations.teamRelations(ownerTeam),
            fof == null ? Map.of() : fof.categoryView(), effective, pending);
    }

    @javax.annotation.Nullable
    static String playerName(MinecraftServer server, UUID id) {
        var online = server.getPlayerList().getPlayer(id);
        if (online != null) return online.getScoreboardName();
        String remembered = OwnerScoreboardIdentity.get(server).name(id);
        if (remembered != null) return remembered;
        var cache = server.getProfileCache();
        return cache == null ? null : cache.get(id).map(profile -> profile.getName()).orElse(null);
    }

    @javax.annotation.Nullable
    static String teamName(MinecraftServer server, UUID id) {
        String playerName = playerName(server, id);
        if (playerName == null) return null;
        var team = server.getScoreboard().getPlayersTeam(playerName);
        if (team == null) return null;
        String name = team.getName();
        return name.startsWith("steves_army_friendly_") ? null : name;
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
        buf.writeVarInt(msg.effective.size());
        msg.effective.forEach((id, value) -> {
            buf.writeUUID(id); buf.writeByte(value.stance().ordinal()); buf.writeUtf(value.source());
            buf.writeUtf(value.name()); buf.writeBoolean(value.team() != null);
            if (value.team() != null) buf.writeUtf(value.team());
            buf.writeBoolean(value.online());
        });
        buf.writeVarInt(msg.pending.size());
        msg.pending.forEach((id, value) -> {
            buf.writeUUID(id); buf.writeUUID(value.requester()); buf.writeBoolean(value.desired() != null);
            if (value.desired() != null) buf.writeByte(value.desired().ordinal());
            buf.writeBoolean(value.accepted());
        });
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
        Map<UUID, ClientFofState.Effective> effective = new HashMap<>();
        int effectiveCount = buf.readVarInt();
        for (int i = 0; i < effectiveCount; i++) {
            UUID id = buf.readUUID(); FofStance stance = FofStance.fromOrdinal(buf.readByte()); String source = buf.readUtf();
            String name = buf.readUtf(); String team = buf.readBoolean() ? buf.readUtf() : null; boolean online = buf.readBoolean();
            if (stance != null) effective.put(id, new ClientFofState.Effective(stance, source, name, team, online));
        }
        Map<UUID, ClientFofState.Pending> pending = new HashMap<>();
        int pendingCount = buf.readVarInt();
        for (int i = 0; i < pendingCount; i++) {
            UUID id = buf.readUUID(); UUID requester = buf.readUUID();
            FofStance desired = buf.readBoolean() ? FofStance.fromOrdinal(buf.readByte()) : null;
            pending.put(id, new ClientFofState.Pending(requester, desired, buf.readBoolean()));
        }
        return new FofSyncPacket(players, teams, categories, effective, pending);
    }

    public static void handle(FofSyncPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
            () -> () -> ClientFofState.INSTANCE.update(msg.players, msg.teams, msg.categories, msg.effective, msg.pending)));
        ctx.get().setPacketHandled(true);
    }
}
