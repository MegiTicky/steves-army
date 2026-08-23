package com.stevesarmy.combat.cover;

import com.stevesarmy.combat.ThreatAwareness;
import com.stevesarmy.combat.VisibilityRay;
import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.debug.DiagnosticLogManager;
import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.entity.MachineGunnerEntity;
import com.stevesarmy.entity.ai.SoldierCombatGoal;
import com.stevesarmy.squad.SquadCoverContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** Chooses a nearby prone firing lane only when comparable physical cover is blind. */
public final class DefensivePositionSelector {
    public static final float COMPETITIVE_SCORE_DELTA = 0.15f;
    public static final float USABLE_COVER_FIRING_ACCESS = 0.25f;
    private static final double MIN_SQUAD_SPACING = 3.0D;
    private static final double PREFERRED_SQUAD_SPACING = 5.0D;

    private DefensivePositionSelector() { }

    public static Optional<DefensivePositionCandidate.ProneFiringCandidate> selectProne(
        SoldierEntity soldier, @Nullable LivingEntity target, ThreatAwareness threats,
        List<CoverFinder.ScoredCover> covers, SquadCoverContext squadCtx) {
        SoldierCombatGoal combatGoal = soldier.getCombatGoal();
        if (combatGoal == null) {
            trace(soldier, "no_combat_goal", "none", 0, 0, 0, 0, false, 0, -1.0D, false);
            return Optional.empty();
        }
        AimSource aimSource = resolveAimSource(target, threats, combatGoal);
        if (aimSource == null) {
            trace(soldier, "no_threat", "none", 0, 0, 0, 0, false, 0, -1.0D, false);
            return Optional.empty();
        }
        String safetyReason = getSafetyGateReason(soldier, target, combatGoal);
        if (safetyReason != null) {
            trace(soldier, safetyReason, aimSource.label(), 0, 0, 0, 0, false, 0, -1.0D, false);
            return Optional.empty();
        }
        float bestScore = covers.isEmpty() ? 0.0f : (float)covers.get(0).score;
        boolean usablePhysicalCover = covers.stream().anyMatch(sc -> sc.score >= bestScore - COMPETITIVE_SCORE_DELTA
            && sc.cover.getFiringAccessScore() >= USABLE_COVER_FIRING_ACCESS);
        if (usablePhysicalCover) {
            trace(soldier, "physical_firing_cover", aimSource.label(), 0, 0, 0, 0, true, 0, -1.0D, false);
            return Optional.empty();
        }

        ScanResult scan = scanProneLanes(soldier, aimSource.aimPoint(), soldier.level());
        SpacingResult spacing = selectWithSpacing(scan.candidates(), squadCtx.getDefensivePositions());
        trace(soldier, spacing.candidate() != null ? "selected" : "no_valid_lane", aimSource.label(), scan.terrainValid(),
            scan.proneLos(), scan.pathValid(), scan.pathRejected(), false, squadCtx.getDefensivePositions().size(),
            spacing.closestPeerDistance(), spacing.usedFallback());
        return Optional.ofNullable(spacing.candidate());
    }

    @Nullable
    private static String getSafetyGateReason(SoldierEntity soldier, @Nullable LivingEntity target,
                                               SoldierCombatGoal combatGoal) {
        if (soldier.hasValidPingMoveTarget()) return "movement_command";
        if (target != null && target.isAlive() && soldier.distanceTo(target) <= 10.0) return "close_target";
        for (LivingEntity other : combatGoal.getPotentialTargets()) {
            if (other != target && other.isAlive() && !soldier.isFriendlyTo(other)
                && soldier.distanceTo(other) <= 10.0) return "nearby_threat";
        }
        return null;
    }

    @Nullable
    private static AimSource resolveAimSource(@Nullable LivingEntity target, ThreatAwareness threats,
                                              SoldierCombatGoal combatGoal) {
        if (target != null && target.isAlive()) {
            return new AimSource(combatGoal.getProneFiringAimPoint(target), "target");
        }
        BlockPos threatPos = threats.getPrimaryThreatPosition();
        if (threatPos == null) return null;
        return new AimSource(new Vec3(threatPos.getX() + .5, threatPos.getY() + 1.35, threatPos.getZ() + .5),
            "threat_memory");
    }

    private static ScanResult scanProneLanes(
        SoldierEntity soldier, Vec3 aimPoint, Level level) {
        java.util.ArrayList<DefensivePositionCandidate.ProneFiringCandidate> result = new java.util.ArrayList<>();
        BlockPos origin = soldier.blockPosition();
        int terrainValid = 0;
        int proneLos = 0;
        int pathValid = 0;
        int pathRejected = 0;
        for (int y = -1; y <= 1; y++) for (int x = -5; x <= 5; x++) for (int z = -5; z <= 5; z++) {
            if (x * x + z * z > 25) continue;
            BlockPos pos = origin.offset(x, y, z);
            if (!isProneTerrainValid(level, pos)) continue;
            terrainValid++;
            Vec3 eye = new Vec3(pos.getX() + .5, pos.getY() + .45, pos.getZ() + .5);
            VisibilityRay.Result visibility = VisibilityRay.trace(level, eye, aimPoint, soldier);
            boolean firingLaneAccepted = soldier instanceof MachineGunnerEntity
                ? visibility.firingLaneQuality() >= CoverFinder.MIN_RELIABLE_FIRING_LANE
                : visibility.hasContact();
            if (!firingLaneAccepted) continue;
            proneLos++;
            boolean currentPosition = pos.equals(origin);
            if (!currentPosition) {
                net.minecraft.world.level.pathfinder.Path path = soldier.getNavigation().createPath(pos, 0);
                if (path == null || !path.canReach()) {
                    pathRejected++;
                    continue;
                }
            }
            pathValid++;
            float distance = (float)Math.sqrt(x * x + z * z);
            float protection = adjacentCover(level, pos) ? .15f : 0.0f;
            result.add(new DefensivePositionCandidate.ProneFiringCandidate(pos, 1.0f, protection,
                0.0f, distance / 5.0f, currentPosition ? "prone-current-lane" : "prone-lane"));
        }
        return new ScanResult(result, terrainValid, proneLos, pathValid, pathRejected);
    }

    private static SpacingResult selectWithSpacing(
        List<DefensivePositionCandidate.ProneFiringCandidate> candidates, List<BlockPos> peerPositions) {
        List<DefensivePositionCandidate.ProneFiringCandidate> spaced = candidates.stream()
            .filter(candidate -> closestPeerDistance(candidate.destination(), peerPositions) >= MIN_SQUAD_SPACING)
            .toList();
        boolean fallback = spaced.isEmpty() && !candidates.isEmpty() && !peerPositions.isEmpty();
        List<DefensivePositionCandidate.ProneFiringCandidate> choices = spaced.isEmpty() ? candidates : spaced;
        DefensivePositionCandidate.ProneFiringCandidate selected = choices.stream().max(Comparator.comparingDouble(
            candidate -> candidate.firingAccess() + candidate.protection() - candidate.routeExposure()
                - candidate.movementCost() + spacingScore(closestPeerDistance(candidate.destination(), peerPositions))
        )).orElse(null);
        return new SpacingResult(selected,
            selected != null ? closestPeerDistance(selected.destination(), peerPositions) : -1.0D, fallback);
    }

    private static double closestPeerDistance(BlockPos candidate, List<BlockPos> peerPositions) {
        double closest = Double.POSITIVE_INFINITY;
        for (BlockPos peer : peerPositions) {
            double dx = candidate.getX() - peer.getX();
            double dz = candidate.getZ() - peer.getZ();
            closest = Math.min(closest, Math.sqrt(dx * dx + dz * dz));
        }
        return closest;
    }

    private static double spacingScore(double closestPeerDistance) {
        if (!Double.isFinite(closestPeerDistance) || closestPeerDistance <= MIN_SQUAD_SPACING) return 0.0D;
        return Math.min(1.0D, (closestPeerDistance - MIN_SQUAD_SPACING)
            / (PREFERRED_SQUAD_SPACING - MIN_SQUAD_SPACING));
    }

    private static void trace(SoldierEntity soldier, String reason, String threatSource, int terrainValid, int proneLos,
                              int pathValid, int pathRejected, boolean physicalCover, int peerCount,
                              double closestPeerDistance, boolean spacingFallback) {
        if (DiagnosticLogManager.isCoverLoggingEnabled() && soldier.tickCount % 20 == 0) {
            String closest = closestPeerDistance < 0.0D || !Double.isFinite(closestPeerDistance)
                ? "none" : String.format(java.util.Locale.ROOT, "%.1f", closestPeerDistance);
            StevesArmyMod.LOGGER.info("[DefensivePosition] soldier={} result={} source={} terrain={} proneLos={} paths={} pathRejected={} physicalFiringCover={} peers={} closestPeer={} spacingFallback={}",
                soldier.getId(), reason, threatSource, terrainValid, proneLos, pathValid, pathRejected, physicalCover,
                peerCount, closest, spacingFallback);
        }
    }

    private record AimSource(Vec3 aimPoint, String label) { }

    private record ScanResult(List<DefensivePositionCandidate.ProneFiringCandidate> candidates,
                              int terrainValid, int proneLos, int pathValid, int pathRejected) { }

    private record SpacingResult(@Nullable DefensivePositionCandidate.ProneFiringCandidate candidate,
                                 double closestPeerDistance, boolean usedFallback) { }

    private static boolean isProneTerrainValid(Level level, BlockPos pos) {
        return level.isLoaded(pos) && level.getBlockState(pos.below()).isSolid()
            && level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()
            && level.getBlockState(pos.above()).getCollisionShape(level, pos.above()).isEmpty()
            && level.getBlockState(pos).getFluidState().isEmpty();
    }

    private static boolean adjacentCover(Level level, BlockPos pos) {
        for (net.minecraft.core.Direction d : net.minecraft.core.Direction.Plane.HORIZONTAL) {
            if (!level.getBlockState(pos.relative(d)).getCollisionShape(level, pos.relative(d)).isEmpty()) return true;
        }
        return false;
    }
}
