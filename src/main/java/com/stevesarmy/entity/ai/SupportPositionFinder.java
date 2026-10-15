package com.stevesarmy.entity.ai;

import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.combat.VisibilityRay;
import com.stevesarmy.combat.cover.ExactPathValidationBudget;
import com.stevesarmy.debug.DiagnosticLogManager;
import com.stevesarmy.entity.MachineGunnerEntity;
import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.entity.SupportEntity;
import com.stevesarmy.squad.SquadData;
import com.stevesarmy.squad.SquadManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Computes a rear support position for machine gunners: behind the squad's
 * rifleman line relative to the active engagement area. The position is only a
 * search anchor; the cover machinery snaps it to an actual cover block.
 */
public final class SupportPositionFinder {
    private static final double REAR_ANCHOR_OFFSET_BLOCKS = 3.0;
    private static final double SUPPORT_OFFSET_BLOCKS = 10.0;
    private static final double LATERAL_OFFSET_BLOCKS = 4.0;
    private static final double MIN_AWAY_DISTANCE_SQ = 0.01;
    private static final double MASKING_RADIUS = 2.0;
    private static final double MASKING_WEIGHT = 2.0;
    private static final double LOS_WEIGHT = 2.5;
    private static final double PATH_WEIGHT = 2.0;
    private static final double MOVEMENT_WEIGHT = 0.05;
    private static final double ECHELON_BONUS = 0.35;

    private SupportPositionFinder() {}

    /**
     * Returns a small rearward search anchor for the MG relative to the
     * squad's rifleman line. This is intentionally separate from the larger
     * support-position evaluator below: it biases cover discovery without
     * forcing the MG into a fixed formation slot.
     */
    @Nullable
    public static BlockPos findRearAnchor(SoldierEntity soldier, @Nullable BlockPos forwardTarget) {
        Vec3 line = lineAnchor(soldier);
        if (line == null) {
            return null;
        }

        if (forwardTarget == null) {
            LivingEntity owner = soldier.getOwner();
            if (owner == null) {
                return null;
            }
            forwardTarget = owner.blockPosition();
        }

        Vec3 towardTarget = Vec3.atCenterOf(forwardTarget).subtract(line);
        if (towardTarget.horizontalDistanceSqr() < MIN_AWAY_DISTANCE_SQ) {
            return null;
        }
        Vec3 rearDirection = new Vec3(-towardTarget.x, 0.0, -towardTarget.z).normalize();
        Vec3 anchor = line.add(rearDirection.scale(REAR_ANCHOR_OFFSET_BLOCKS));
        return snapToGround(soldier.level(), BlockPos.containing(anchor));
    }

    @Nullable
    public static BlockPos findSupportPosition(MachineGunnerEntity mg) {
        Vec3 threatPos = engagementCenter(mg);
        if (threatPos == null) {
            return null;
        }

        Vec3 anchor = lineAnchor(mg);
        if (anchor == null) {
            return null;
        }

        Vec3 awayDir = anchor.subtract(threatPos);
        if (awayDir.lengthSqr() < MIN_AWAY_DISTANCE_SQ) {
            return null;
        }
        awayDir = awayDir.normalize();

        Vec3 rearAxis = anchor.add(awayDir.scale(SUPPORT_OFFSET_BLOCKS));
        Vec3 lateral = new Vec3(-awayDir.z, 0.0, awayDir.x);
        List<AnchorCandidate> candidates = new ArrayList<>(3);
        ExactPathValidationBudget pathBudget = new ExactPathValidationBudget();
        addCandidate(candidates, mg, threatPos, rearAxis, lateral, LATERAL_OFFSET_BLOCKS, pathBudget);
        addCandidate(candidates, mg, threatPos, rearAxis, lateral, 0.0, pathBudget);
        addCandidate(candidates, mg, threatPos, rearAxis, lateral, -LATERAL_OFFSET_BLOCKS, pathBudget);

        AnchorCandidate selected = null;
        for (AnchorCandidate candidate : candidates) {
            if (selected == null || candidate.score() > selected.score()) {
                selected = candidate;
            }
        }
        if (selected == null) {
            return null;
        }

        if (DiagnosticLogManager.isCoverLoggingEnabled()) {
            StevesArmyMod.LOGGER.info(
                "[SupportAnchor] MG {} selected={} offset={} score={} candidates={}",
                mg.getId(), selected.position(), format(selected.lateralOffset()),
                format(selected.score()), formatCandidates(candidates));
        }
        return selected.position();
    }

    private static void addCandidate(List<AnchorCandidate> candidates, SoldierEntity soldier,
                                     Vec3 threatPos, Vec3 rearAxis,
                                     Vec3 lateral, double lateralOffset,
                                     ExactPathValidationBudget pathBudget) {
        Vec3 target = rearAxis.add(lateral.scale(lateralOffset));
        BlockPos position = snapToGround(soldier.level(), BlockPos.containing(target));
        boolean terrainValid = isValidAnchorCell(soldier.level(), position);
        Path path = !terrainValid || position.equals(soldier.blockPosition()) || !pathBudget.tryAcquire()
            ? null : soldier.getNavigation().createPath(position, 0);
        boolean reachable = terrainValid
            && (position.equals(soldier.blockPosition()) || (path != null && path.canReach()));

        Vec3 eye = position.getCenter().add(0.0, 1.6, 0.0);
        VisibilityRay.Result visibility = VisibilityRay.traceIgnoringSmoke(
            soldier.level(), eye, threatPos, soldier);
        double masking = friendlyMasking(soldier, position, threatPos);
        double movement = Math.sqrt(soldier.position().distanceToSqr(position.getCenter()));
        double score = (reachable ? PATH_WEIGHT : -PATH_WEIGHT * 2.0)
            + LOS_WEIGHT * visibility.firingLaneQuality()
            - MASKING_WEIGHT * masking
            - MOVEMENT_WEIGHT * movement
            + (Math.abs(lateralOffset) > 0.01 ? ECHELON_BONUS : 0.0);
        candidates.add(new AnchorCandidate(position, lateralOffset, score, masking,
            visibility.firingLaneQuality(), reachable));
    }

    private static double friendlyMasking(SoldierEntity soldier, BlockPos position,
                                          Vec3 threatPos) {
        List<SoldierEntity> riflemen = riflemen(soldier);
        if (riflemen.isEmpty()) {
            return 0.0;
        }

        Vec3 from = position.getCenter();
        double masking = 0.0;
        for (SoldierEntity rifleman : riflemen) {
            Vec3 member = rifleman.position();
            double projection = segmentProjection(from, threatPos, member);
            if (projection < 0.05 || projection > 0.95) {
                continue;
            }
            double distance = horizontalDistanceToSegment(from, threatPos, member);
            if (distance < MASKING_RADIUS) {
                masking += (MASKING_RADIUS - distance) / MASKING_RADIUS;
            }
        }
        return masking;
    }

    private static double segmentProjection(Vec3 from, Vec3 to, Vec3 point) {
        double dx = to.x - from.x;
        double dz = to.z - from.z;
        double lengthSqr = dx * dx + dz * dz;
        if (lengthSqr < MIN_AWAY_DISTANCE_SQ) {
            return 0.0;
        }
        return ((point.x - from.x) * dx + (point.z - from.z) * dz) / lengthSqr;
    }

    private static double horizontalDistanceToSegment(Vec3 from, Vec3 to, Vec3 point) {
        double projection = Math.max(0.0, Math.min(1.0, segmentProjection(from, to, point)));
        double closestX = from.x + (to.x - from.x) * projection;
        double closestZ = from.z + (to.z - from.z) * projection;
        double dx = point.x - closestX;
        double dz = point.z - closestZ;
        return Math.sqrt(dx * dx + dz * dz);
    }

    @Nullable
    private static Vec3 engagementCenter(MachineGunnerEntity mg) {
        BlockPos center = mg.getSuppressionCenter();
        return center != null ? center.getCenter() : null;
    }

    /** Average position of the squad's riflemen; falls back to the owner. */
    @Nullable
    private static Vec3 lineAnchor(SoldierEntity soldier) {
        List<SoldierEntity> riflemen = riflemen(soldier);
        if (!riflemen.isEmpty()) {
            double x = 0.0;
            double y = 0.0;
            double z = 0.0;
            for (SoldierEntity s : riflemen) {
                x += s.getX();
                y += s.getY();
                z += s.getZ();
            }
            return new Vec3(x / riflemen.size(), y / riflemen.size(), z / riflemen.size());
        }

        LivingEntity owner = soldier.getOwner();
        return owner != null ? owner.position() : null;
    }

    private static List<SoldierEntity> riflemen(SoldierEntity soldier) {
        Level level = soldier.level();
        UUID squadId = soldier.getSquadId();
        if (squadId == null || !(level instanceof ServerLevel serverLevel)) {
            return List.of();
        }
        SquadData squad = SquadManager.get(serverLevel).getSquadById(squadId).orElse(null);
        if (squad == null) {
            return List.of();
        }
        List<SoldierEntity> riflemen = new ArrayList<>();
        for (UUID memberId : squad.getMemberIds()) {
            if (memberId.equals(soldier.getUUID())) {
                continue;
            }
            if (serverLevel.getEntity(memberId) instanceof SoldierEntity s
                && !(s instanceof MachineGunnerEntity)
                && !(s instanceof SupportEntity)) {
                riflemen.add(s);
            }
        }
        return riflemen;
    }

    private static BlockPos snapToGround(Level level, BlockPos pos) {
        int surfaceY = level.getHeightmapPos(Heightmap.Types.WORLD_SURFACE, pos).getY();
        return new BlockPos(pos.getX(), surfaceY, pos.getZ());
    }

    private static boolean isValidAnchorCell(Level level, BlockPos pos) {
        return level.isLoaded(pos)
            && level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()
            && level.getBlockState(pos.below()).isSolid();
    }

    private static String format(double value) {
        return String.format(java.util.Locale.ROOT, "%.2f", value);
    }

    private static String formatCandidates(List<AnchorCandidate> candidates) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < candidates.size(); i++) {
            if (i > 0) {
                result.append(';');
            }
            AnchorCandidate candidate = candidates.get(i);
            result.append(format(candidate.lateralOffset()))
                .append(':').append(candidate.position())
                .append('/').append(format(candidate.score()))
                .append(" mask=").append(format(candidate.masking()))
                .append(" los=").append(format(candidate.losQuality()))
                .append(" path=").append(candidate.reachable());
        }
        return result.toString();
    }

    private record AnchorCandidate(BlockPos position, double lateralOffset, double score,
                                   double masking, double losQuality, boolean reachable) {}
}
