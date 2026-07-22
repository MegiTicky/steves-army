package com.stevesarmy.util;

import com.stevesarmy.StevesArmyConfig;
import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.squad.FireTeam;
import com.stevesarmy.squad.SquadManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

public class SpacingHelper {

    public static BlockPos applySpacing(BlockPos target, SoldierEntity soldier) {
        if (soldier.isCQB()) return target;

        BlockPos offset = soldier.getFormationOffset();
        if (offset == null) {
            offset = computeOffset(target, soldier);
            StevesArmyMod.LOGGER.info("[Spacing] Soldier {} assigned offset {} for target {}",
                soldier.getId(), offset, target);
            soldier.setFormationOffset(offset);
        }
        return target.offset(offset);
    }

    private static BlockPos computeOffset(BlockPos target, SoldierEntity soldier) {
        double spacing = StevesArmyConfig.getSpacingDistance();
        FireTeam myTeam = soldier.getFireTeam();

        List<SoldierEntity> team = getAuthoritativeTeam(soldier, myTeam);
        if (team.isEmpty()) {
            return BlockPos.ZERO;
        }

        team.sort(Comparator.comparing(e -> e.getUUID()));
        int myIndex = team.indexOf(soldier);
        int teamSize = team.size();

        StevesArmyMod.LOGGER.info("[Spacing] computeOffset soldier={} myIndex={}/{}",
            soldier.getId(), myIndex, teamSize);

        // Alternating left-right pattern: 0, +spacing, -spacing, +2*spacing, -2*spacing
        if (myIndex == 0) return BlockPos.ZERO;
        int step = (myIndex + 1) / 2;
        boolean isRight = (myIndex % 2 != 0);

        // Compute offset perpendicular to movement direction from target to soldier
        Vec3 toSoldier = soldier.position().subtract(Vec3.atCenterOf(target));
        double dx, dz;
        if (toSoldier.horizontalDistanceSqr() < 0.01) {
            // Soldier is on the target; fall back to world X
            dx = isRight ? step * spacing : -step * spacing;
            dz = 0;
        } else {
            Vec3 perp = new Vec3(-toSoldier.z, 0, toSoldier.x).normalize();
            double sign = isRight ? 1 : -1;
            dx = perp.x * step * spacing;
            dz = perp.z * step * spacing;
        }

        int nominalX = (int) Math.round(dx);
        int nominalZ = (int) Math.round(dz);

        // Try the nominal offset; if unpathable, search nearby alternatives
        BlockPos best = findWalkableOffset(target, nominalX, nominalZ, soldier);
        if (best != null) {
            return best;
        }

        // Fallback: try smaller steps along perpendicular
        for (int s = 1; s <= step; s++) {
            double frac = (double) s / step;
            int tryX = (int) Math.round(dx * frac);
            int tryZ = (int) Math.round(dz * frac);
            BlockPos candidate = findWalkableOffset(target, tryX, tryZ, soldier);
            if (candidate != null) return candidate;
        }

        return BlockPos.ZERO;
    }

    private static List<SoldierEntity> getAuthoritativeTeam(SoldierEntity soldier, FireTeam myTeam) {
        List<SoldierEntity> result = new ArrayList<>();
        if (!(soldier.level() instanceof ServerLevel serverLevel)) {
            // Client-side: fall back to nearby check (best effort)
            for (LivingEntity nearby : soldier.level().getEntitiesOfClass(
                    LivingEntity.class,
                    soldier.getBoundingBox().inflate(32),
                    e -> e != soldier && e.isAlive())) {
                if (nearby instanceof SoldierEntity other) {
                    if (myTeam != FireTeam.ALL && other.getFireTeam() != myTeam) continue;
                    if (other.getSquadId() == null || !other.getSquadId().equals(soldier.getSquadId())) continue;
                    result.add(other);
                }
            }
            result.add(soldier);
            return result;
        }

        UUID squadId = soldier.getSquadId();
        if (squadId == null) {
            result.add(soldier);
            return result;
        }

        SquadManager mgr = SquadManager.get(serverLevel);
        List<LivingEntity> members = mgr.getSquadMembers(serverLevel, squadId, null);
        for (LivingEntity member : members) {
            if (member instanceof SoldierEntity s && s.isAlive()) {
                if (myTeam == FireTeam.ALL || s.getFireTeam() == myTeam) {
                    result.add(s);
                }
            }
        }
        result.add(soldier);
        return result;
    }

    private static BlockPos findWalkableOffset(BlockPos target, int dx, int dz, SoldierEntity soldier) {
        BlockPos candidate = target.offset(dx, 0, dz);
        if (isWalkable(candidate, soldier)) {
            return new BlockPos(dx, 0, dz);
        }
        // Try adjacent positions within 1 block of the nominal offset
        for (int ox = -1; ox <= 1; ox++) {
            for (int oz = -1; oz <= 1; oz++) {
                if (ox == 0 && oz == 0) continue;
                BlockPos adj = target.offset(dx + ox, 0, dz + oz);
                if (isWalkable(adj, soldier)) {
                    return new BlockPos(dx + ox, 0, dz + oz);
                }
            }
        }
        return null;
    }

    private static boolean isWalkable(BlockPos pos, SoldierEntity soldier) {
        if (!soldier.level().isInWorldBounds(pos)) return false;
        if (!soldier.level().getBlockState(pos).isAir()) return false;
        if (soldier.level().getBlockState(pos.below()).isAir()) return false;
        // Check the block above for headroom
        if (!soldier.level().getBlockState(pos.above()).isAir()) return false;
        return true;
    }

    public static List<SoldierEntity> getFireTeamSoldiers(
            List<? extends LivingEntity> allMembers,
            SoldierEntity soldier) {
        FireTeam myTeam = soldier.getFireTeam();
        List<SoldierEntity> result = new ArrayList<>();
        for (LivingEntity member : allMembers) {
            if (member instanceof SoldierEntity s && s.isAlive()) {
                if (myTeam == FireTeam.ALL || s.getFireTeam() == myTeam) {
                    result.add(s);
                }
            }
        }
        return result;
    }
}