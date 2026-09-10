package com.stevesarmy.squad;

import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.entity.SoldierRole;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/** Shared player-order targeting: which soldiers a wheel/ping command applies to. */
public final class SquadTargeting {
    private SquadTargeting() {}

    /**
     * Resolves the soldiers a player order applies to: the sender's squad members (if they lead
     * one) plus nearby owned soldiers as a fallback, optionally narrowed to a fire-team scope.
     */
    public static List<SoldierEntity> resolveOrderedSoldiers(ServerLevel level, ServerPlayer sender, FireTeam scope) {
        SquadManager mgr = SquadManager.get(level);
        java.util.Optional<SquadData> squadOpt = mgr.getSquadByLeader(sender.getUUID());
        if (squadOpt.isEmpty()) {
            // No squad — fall back to nearby owned soldiers (within 100 blocks)
            return level.getEntitiesOfClass(
                SoldierEntity.class,
                sender.getBoundingBox().inflate(100),
                s -> s.isOwnedBy(sender) && s.getRole() != SoldierRole.VEHICLE_CREW
            );
        }

        SquadData squad = squadOpt.get();
        List<LivingEntity> members = mgr.getSquadMembers(level, squad.getSquadId(), null);
        List<SoldierEntity> result = new ArrayList<>();
        for (LivingEntity member : members) {
            if (member instanceof SoldierEntity s && s.isAlive() && s.isOwnedBy(sender)
                && s.getFireTeam() != FireTeam.GARRISON
                && s.getRole() != SoldierRole.VEHICLE_CREW) {
                result.add(s);
            }
        }
        // Add any owned soldiers not in squad that are within 100 blocks as a fallback (UUID dedup)
        java.util.Set<UUID> resultUuids = result.stream().map(s -> s.getUUID()).collect(java.util.stream.Collectors.toSet());
        List<SoldierEntity> nearbyFallback = level.getEntitiesOfClass(
            SoldierEntity.class,
            sender.getBoundingBox().inflate(100),
            s -> s.isOwnedBy(sender) && s.getFireTeam() != FireTeam.GARRISON
                && s.getRole() != SoldierRole.VEHICLE_CREW
                && !resultUuids.contains(s.getUUID())
        );
        result.addAll(nearbyFallback);

        // Filter by fire team scope
        if (scope != FireTeam.ALL) {
            FireTeamAssignment fta = FireTeamAssignment.get(level, sender.getUUID());
            List<UUID> teamIds = fta.getSoldiersInTeam(scope);
            result = result.stream()
                .filter(s -> teamIds.contains(s.getUUID()))
                .collect(Collectors.toList());
        }

        if (result.isEmpty()) {
            StevesArmyMod.LOGGER.warn("SquadTargeting: no owned soldiers found for player {} (squad={})",
                sender.getName().getString(), squadOpt.isPresent());
        }

        return result;
    }
}
