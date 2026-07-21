package com.stevesarmy.squad;

import com.stevesarmy.StevesArmyMod;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.*;

public class FireTeamAssignment extends SavedData {
    private static final String DATA_NAME = "steves_army_fire_teams";

    private final UUID leaderId;
    private int teamCount = 2;
    private final Map<FireTeam, List<UUID>> teams = new HashMap<>();

    public FireTeamAssignment(UUID leaderId) {
        this.leaderId = leaderId;
        for (FireTeam ft : getActiveTeams()) {
            teams.put(ft, new ArrayList<>());
        }
    }

    public int getTeamCount() { return teamCount; }

    public void setTeamCount(int count) {
        this.teamCount = Math.max(1, Math.min(4, count));
        for (FireTeam ft : FireTeam.values()) {
            if (ft == FireTeam.ALL) continue;
            if (isActive(ft)) {
                teams.putIfAbsent(ft, new ArrayList<>());
            } else {
                List<UUID> moved = teams.remove(ft);
                if (moved != null) {
                    teams.get(getActiveTeams().get(0)).addAll(moved);
                }
            }
        }
        setDirty();
    }

    public List<FireTeam> getActiveTeams() {
        List<FireTeam> active = new ArrayList<>();
        for (FireTeam ft : FireTeam.values()) {
            if (ft == FireTeam.ALL) continue;
            if (ft.ordinal() <= teamCount) {
                active.add(ft);
            }
        }
        return active;
    }

    private boolean isActive(FireTeam ft) {
        return ft != FireTeam.ALL && ft.ordinal() <= teamCount;
    }

    public FireTeam getTeamFor(UUID soldierId) {
        for (Map.Entry<FireTeam, List<UUID>> entry : teams.entrySet()) {
            if (entry.getValue().contains(soldierId)) {
                return entry.getKey();
            }
        }
        return getActiveTeams().get(0);
    }

    public void assignToTeam(UUID soldierId, FireTeam team) {
        for (List<UUID> list : teams.values()) {
            list.remove(soldierId);
        }
        if (isActive(team)) {
            teams.get(team).add(soldierId);
        } else {
            teams.get(getActiveTeams().get(0)).add(soldierId);
        }
        setDirty();
    }

    public void rebalance(List<UUID> allSoldierIds) {
        for (List<UUID> list : teams.values()) {
            list.clear();
        }
        List<FireTeam> active = getActiveTeams();
        if (active.isEmpty()) return;
        List<UUID> sorted = new ArrayList<>(allSoldierIds);
        sorted.sort(Comparator.naturalOrder());
        int idx = 0;
        for (UUID id : sorted) {
            teams.get(active.get(idx % active.size())).add(id);
            idx++;
        }
        setDirty();
    }

    public List<UUID> getSoldiersInTeam(FireTeam team) {
        if (team == FireTeam.ALL) {
            List<UUID> all = new ArrayList<>();
            for (List<UUID> list : teams.values()) {
                all.addAll(list);
            }
            return all;
        }
        return teams.getOrDefault(team, Collections.emptyList());
    }

    public static FireTeamAssignment get(ServerLevel level, UUID leaderId) {
        return level.getDataStorage().computeIfAbsent(
            tag -> load(tag, leaderId),
            () -> new FireTeamAssignment(leaderId),
            DATA_NAME + "_" + leaderId.toString().substring(0, 8)
        );
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        tag.putInt("TeamCount", teamCount);
        for (Map.Entry<FireTeam, List<UUID>> entry : teams.entrySet()) {
            ListTag list = new ListTag();
            for (UUID uuid : entry.getValue()) {
                list.add(StringTag.valueOf(uuid.toString()));
            }
            tag.put("Team_" + entry.getKey().name(), list);
        }
        return tag;
    }

    private static FireTeamAssignment load(CompoundTag tag, UUID leaderId) {
        FireTeamAssignment fta = new FireTeamAssignment(leaderId);
        fta.teamCount = tag.getInt("TeamCount");
        for (FireTeam ft : fta.getActiveTeams()) {
            ListTag list = tag.getList("Team_" + ft.name(), 8);
            List<UUID> uuids = new ArrayList<>();
            for (int i = 0; i < list.size(); i++) {
                try {
                    uuids.add(UUID.fromString(list.getString(i)));
                } catch (Exception e) {
                    StevesArmyMod.LOGGER.warn("Failed to parse UUID in fire team {}: {}", ft, list.getString(i));
                }
            }
            fta.teams.put(ft, uuids);
        }
        return fta;
    }
}