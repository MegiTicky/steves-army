package com.stevesarmy.squad;

import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.entity.SoldierEntity;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.*;

public class FireTeamAssignment extends SavedData {
    private static final String DATA_NAME = "steves_army_fire_teams";

    private final UUID leaderId;
    private int teamCount = 2;
    private FireTeam selectedSpawnTeam = FireTeam.ALPHA;
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
        if (!isActive(selectedSpawnTeam)) {
            selectedSpawnTeam = getActiveTeams().get(0);
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

    public FireTeam getSelectedSpawnTeam() {
        return isActive(selectedSpawnTeam) ? selectedSpawnTeam : getActiveTeams().get(0);
    }

    public void setSelectedSpawnTeam(FireTeam team) {
        if (isActive(team)) {
            selectedSpawnTeam = team;
            setDirty();
        }
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

    /**
     * Evenly distributes each exact main-hand item type, then uses soldier positions to
     * keep the members assigned to each team close together wherever those quotas allow.
     */
    public void rebalance(List<SoldierEntity> soldiers, Vec3 leaderPosition) {
        for (List<UUID> list : teams.values()) {
            list.clear();
        }
        List<FireTeam> active = getActiveTeams();
        if (active.isEmpty()) return;

        Map<String, List<SoldierEntity>> byWeapon = new TreeMap<>();
        for (SoldierEntity soldier : soldiers) {
            byWeapon.computeIfAbsent(getWeaponId(soldier), ignored -> new ArrayList<>()).add(soldier);
        }

        List<Map.Entry<String, List<SoldierEntity>>> weaponGroups = new ArrayList<>(byWeapon.entrySet());
        weaponGroups.sort(Comparator
            .<Map.Entry<String, List<SoldierEntity>>>comparingInt(entry -> entry.getValue().size())
            .reversed()
            .thenComparing(Map.Entry::getKey));

        Map<FireTeam, List<SoldierEntity>> assigned = new EnumMap<>(FireTeam.class);
        Map<FireTeam, Integer> teamSizes = new EnumMap<>(FireTeam.class);
        for (FireTeam team : active) {
            assigned.put(team, new ArrayList<>());
            teamSizes.put(team, 0);
        }

        for (Map.Entry<String, List<SoldierEntity>> weaponGroup : weaponGroups) {
            List<SoldierEntity> pending = new ArrayList<>(weaponGroup.getValue());
            pending.sort(Comparator.comparing(SoldierEntity::getUUID));

            Map<FireTeam, Integer> quotas = createWeaponQuotas(pending.size(), active, teamSizes);
            while (!pending.isEmpty()) {
                FireTeam team = selectTeamWithEmptyRoster(active, quotas, assigned);
                SoldierEntity soldier;
                if (team != null) {
                    soldier = selectSeedSoldier(pending, assigned, leaderPosition);
                } else {
                    AssignmentCandidate candidate = selectClosestAssignment(pending, active, quotas, assigned, teamSizes);
                    if (candidate == null) {
                        throw new IllegalStateException("No eligible fire team while rebalancing weapon group " + weaponGroup.getKey());
                    }
                    team = candidate.team();
                    soldier = candidate.soldier();
                }

                teams.get(team).add(soldier.getUUID());
                assigned.get(team).add(soldier);
                teamSizes.merge(team, 1, Integer::sum);
                quotas.merge(team, -1, Integer::sum);
                pending.remove(soldier);
            }
        }
        setDirty();
    }

    private static String getWeaponId(SoldierEntity soldier) {
        ResourceLocation weaponId = ForgeRegistries.ITEMS.getKey(soldier.getMainHandItem().getItem());
        return weaponId == null ? "minecraft:air" : weaponId.toString();
    }

    private static Map<FireTeam, Integer> createWeaponQuotas(
        int groupSize, List<FireTeam> active, Map<FireTeam, Integer> teamSizes
    ) {
        Map<FireTeam, Integer> quotas = new EnumMap<>(FireTeam.class);
        int baseQuota = groupSize / active.size();
        int remainder = groupSize % active.size();
        for (FireTeam team : active) {
            quotas.put(team, baseQuota);
        }

        // Put remainder weapons in the least-populated teams to keep total team sizes balanced.
        for (int i = 0; i < remainder; i++) {
            FireTeam team = active.stream()
                .min(Comparator
                    .comparingInt((FireTeam candidate) -> teamSizes.get(candidate) + quotas.get(candidate))
                    .thenComparingInt(Enum::ordinal))
                .orElseThrow();
            quotas.merge(team, 1, Integer::sum);
        }
        return quotas;
    }

    private static FireTeam selectTeamWithEmptyRoster(
        List<FireTeam> active, Map<FireTeam, Integer> quotas, Map<FireTeam, List<SoldierEntity>> assigned
    ) {
        return active.stream()
            .filter(team -> quotas.get(team) > 0 && assigned.get(team).isEmpty())
            .findFirst()
            .orElse(null);
    }

    private static SoldierEntity selectSeedSoldier(
        List<SoldierEntity> pending, Map<FireTeam, List<SoldierEntity>> assigned, Vec3 leaderPosition
    ) {
        List<SoldierEntity> existing = assigned.values().stream()
            .flatMap(Collection::stream)
            .toList();
        if (existing.isEmpty()) {
            return pending.stream()
                .min(Comparator
                    .comparingDouble((SoldierEntity soldier) -> soldier.position().distanceToSqr(leaderPosition))
                    .thenComparing(SoldierEntity::getUUID))
                .orElseThrow();
        }

        // Seed a new team away from the existing clusters before filling teams by proximity.
        return pending.stream()
            .max(Comparator
                .comparingDouble((SoldierEntity soldier) -> nearestDistanceSqr(soldier, existing))
                .thenComparing(SoldierEntity::getUUID))
            .orElseThrow();
    }

    private static AssignmentCandidate selectClosestAssignment(
        List<SoldierEntity> pending,
        List<FireTeam> active,
        Map<FireTeam, Integer> quotas,
        Map<FireTeam, List<SoldierEntity>> assigned,
        Map<FireTeam, Integer> teamSizes
    ) {
        AssignmentCandidate best = null;
        for (SoldierEntity soldier : pending) {
            for (FireTeam team : active) {
                if (quotas.get(team) <= 0) continue;
                double distanceSqr = nearestDistanceSqr(soldier, assigned.get(team));
                AssignmentCandidate candidate = new AssignmentCandidate(soldier, team, distanceSqr);
                if (best == null || compareCandidates(candidate, best, teamSizes) < 0) {
                    best = candidate;
                }
            }
        }
        return best;
    }

    private static int compareCandidates(
        AssignmentCandidate first, AssignmentCandidate second, Map<FireTeam, Integer> teamSizes
    ) {
        int comparison = Double.compare(first.distanceSqr(), second.distanceSqr());
        if (comparison != 0) return comparison;
        comparison = Integer.compare(teamSizes.get(first.team()), teamSizes.get(second.team()));
        if (comparison != 0) return comparison;
        comparison = Integer.compare(first.team().ordinal(), second.team().ordinal());
        if (comparison != 0) return comparison;
        return first.soldier().getUUID().compareTo(second.soldier().getUUID());
    }

    private static double nearestDistanceSqr(SoldierEntity soldier, Collection<SoldierEntity> others) {
        return others.stream()
            .mapToDouble(other -> soldier.position().distanceToSqr(other.position()))
            .min()
            .orElse(Double.MAX_VALUE);
    }

    private record AssignmentCandidate(SoldierEntity soldier, FireTeam team, double distanceSqr) {}

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
        tag.putInt("SelectedSpawnTeam", getSelectedSpawnTeam().ordinal());
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
        fta.teamCount = Math.max(1, Math.min(4, tag.getInt("TeamCount")));
        if (tag.contains("SelectedSpawnTeam")) {
            int ordinal = tag.getInt("SelectedSpawnTeam");
            if (ordinal >= 0 && ordinal < FireTeam.values().length) {
                fta.selectedSpawnTeam = FireTeam.values()[ordinal];
            }
        }
        if (!fta.isActive(fta.selectedSpawnTeam)) {
            fta.selectedSpawnTeam = fta.getActiveTeams().get(0);
        }
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
