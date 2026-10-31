package com.stevesarmy.client;

import com.stevesarmy.squad.FofCategory;
import com.stevesarmy.squad.FofStance;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.UUID;

/** Client cache of the local player's FoF overrides, rebuilt from FofSyncPacket. */
public class ClientFofState {
    public static final ClientFofState INSTANCE = new ClientFofState();

    private Map<UUID, FofStance> players = Map.of();
    private Map<String, FofStance> teams = Map.of();
    private Map<FofCategory, FofStance> categories = Map.of();
    private Map<UUID, Effective> effective = Map.of();
    private Map<UUID, Pending> pending = Map.of();

    public record Effective(FofStance stance, String source, String name, @Nullable String team, boolean online) {}
    public record Pending(UUID requester, @Nullable FofStance desired, boolean accepted) {}

    public void update(Map<UUID, FofStance> players, Map<String, FofStance> teams,
                       Map<FofCategory, FofStance> categories, Map<UUID, Effective> effective,
                       Map<UUID, Pending> pending) {
        this.players = players;
        this.teams = teams;
        this.categories = categories;
        this.effective = effective;
        this.pending = pending;
    }

    public void clear() {
        update(Map.of(), Map.of(), Map.of(), Map.of(), Map.of());
    }

    @Nullable
    public FofStance getPlayerStance(UUID playerId) {
        return players.get(playerId);
    }

    @Nullable
    public FofStance getTeamStance(String teamName) {
        return teams.get(teamName);
    }

    @Nullable
    public FofStance getCategoryStance(FofCategory category) {
        return categories.get(category);
    }

    @Nullable public Effective getEffective(UUID playerId) { return effective.get(playerId); }
    @Nullable public Pending getPending(UUID playerId) { return pending.get(playerId); }
    public java.util.Set<UUID> knownPlayers() {
        java.util.Set<UUID> ids = new java.util.HashSet<>(players.keySet());
        ids.addAll(pending.keySet());
        ids.addAll(effective.keySet());
        return ids;
    }
}
