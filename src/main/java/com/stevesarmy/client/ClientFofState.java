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

    public void update(Map<UUID, FofStance> players, Map<String, FofStance> teams,
                       Map<FofCategory, FofStance> categories) {
        this.players = players;
        this.teams = teams;
        this.categories = categories;
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
}
