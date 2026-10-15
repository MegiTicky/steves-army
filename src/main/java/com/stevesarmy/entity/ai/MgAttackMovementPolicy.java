package com.stevesarmy.entity.ai;

import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.entity.SoldierRole;
import com.stevesarmy.squad.ClearingCoordinator;
import com.stevesarmy.squad.ContactPlan;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/** Shared spacing rules for a machine gunner advancing with riflemen. */
public final class MgAttackMovementPolicy {
    public static final double REAR_OFFSET = 9.0;
    public static final double CATCH_UP_LAG = 14.0;
    public static final long MIN_DOCK_DWELL_MS = 8_000L;
    public static final double FALLBACK_STEP = 6.0;
    public static final double SPINE_LEG = 16.0;
    public static final double ROW_MARGIN = 1.0;

    private MgAttackMovementPolicy() {}

    public static boolean active(SoldierEntity soldier) {
        return soldier instanceof com.stevesarmy.entity.MachineGunnerEntity
            && soldier.hasValidAttackTarget()
            && ContactPlan.riflemanAveragePosition(soldier) != null;
    }

    /** Local cover pacing yields only when a clearing task is being executed. */
    public static boolean attackFormationActive(SoldierEntity soldier) {
        if (!active(soldier)) return false;
        ClearingCoordinator.ClearingView view = ClearingCoordinator.viewFor(soldier);
        if (view == null) return true;
        if (view.stage() == ClearingCoordinator.ClearingStage.SECURE) return false;
        if (view.stage() == ClearingCoordinator.ClearingStage.CLEAR
            || view.stage() == ClearingCoordinator.ClearingStage.STALLED) {
            return view.assignedPoi() == null;
        }
        return true;
    }

    public static boolean aheadOfRifleLine(SoldierEntity soldier, BlockPos target, BlockPos position) {
        Vec3 average = ContactPlan.riflemanAveragePosition(soldier);
        if (average == null || target == null || position == null) return false;
        Vec3 axis = Vec3.atCenterOf(target).subtract(average);
        axis = new Vec3(axis.x, 0.0, axis.z);
        if (axis.lengthSqr() < 1.0E-6) return false;
        axis = axis.normalize();
        double front = Double.NEGATIVE_INFINITY;
        if (soldier.level() instanceof net.minecraft.server.level.ServerLevel level
            && soldier.getSquadId() != null) {
            for (java.util.UUID id : com.stevesarmy.squad.SquadManager.get(level)
                .getSquadById(soldier.getSquadId()).map(com.stevesarmy.squad.SquadData::getMemberIds)
                .orElse(java.util.List.of())) {
                if (id.equals(soldier.getUUID())) continue;
                if (level.getEntity(id) instanceof SoldierEntity mate && mate.isAlive()
                    && mate.getRole() == SoldierRole.RIFLEMAN) {
                    front = Math.max(front, progress(mate.position(), average, axis));
                }
            }
        }
        return front > Double.NEGATIVE_INFINITY
            && progress(Vec3.atCenterOf(position), average, axis) > front - ROW_MARGIN;
    }

    public static boolean inPreferredRearBand(SoldierEntity soldier, BlockPos target, BlockPos position) {
        Vec3 average = ContactPlan.riflemanAveragePosition(soldier);
        if (average == null || target == null || position == null) return false;
        Vec3 axis = Vec3.atCenterOf(target).subtract(average);
        axis = new Vec3(axis.x, 0.0, axis.z);
        if (axis.lengthSqr() < 1.0E-6) return false;
        double offset = progress(Vec3.atCenterOf(position), average, axis.normalize());
        return offset >= -10.0 && offset <= -8.0;
    }

    public static double rifleLineLag(SoldierEntity soldier, BlockPos position) {
        Vec3 average = ContactPlan.riflemanAveragePosition(soldier);
        BlockPos target = soldier.getAttackTargetPos();
        if (average == null || target == null) return 0.0;
        Vec3 axis = Vec3.atCenterOf(target).subtract(average);
        axis = new Vec3(axis.x, 0.0, axis.z);
        if (axis.lengthSqr() < 1.0E-6) return 0.0;
        axis = axis.normalize();
        return -progress(Vec3.atCenterOf(position), average, axis);
    }

    private static double progress(Vec3 point, Vec3 origin, Vec3 axis) {
        return (point.x - origin.x) * axis.x + (point.z - origin.z) * axis.z;
    }

    public static Vec3 rearAnchor(SoldierEntity soldier, BlockPos target) {
        Vec3 average = ContactPlan.riflemanAveragePosition(soldier);
        if (average == null || target == null) return null;
        Vec3 direction = Vec3.atCenterOf(target).subtract(average);
        direction = new Vec3(direction.x, 0.0, direction.z);
        if (direction.lengthSqr() < 1.0E-6) return null;
        return average.subtract(direction.normalize().scale(REAR_OFFSET));
    }

    /** Caps an attack walk at the preferred rear slot while allowing lateral route bends. */
    public static BlockPos capRearDestination(SoldierEntity soldier, BlockPos destination) {
        Vec3 anchor = rearAnchor(soldier, soldier.getAttackTargetPos());
        if (anchor == null) return destination;
        Vec3 direction = Vec3.atCenterOf(soldier.getAttackTargetPos())
            .subtract(ContactPlan.riflemanAveragePosition(soldier));
        direction = new Vec3(direction.x, 0.0, direction.z).normalize();
        Vec3 average = ContactPlan.riflemanAveragePosition(soldier);
        Vec3 proposed = Vec3.atCenterOf(destination);
        double proposedProgress = (proposed.x - average.x) * direction.x
            + (proposed.z - average.z) * direction.z;
        if (proposedProgress <= -REAR_OFFSET) return destination;
        Vec3 capped = proposed.add(direction.scale(-REAR_OFFSET - proposedProgress));
        return new BlockPos((int)Math.floor(capped.x), destination.getY(), (int)Math.floor(capped.z));
    }

    public static BlockPos capSpineDestination(SoldierEntity soldier,
            com.stevesarmy.entity.ai.pathfinding.AdvanceSpine spine,
            double fromProgress, double endProgress, int y) {
        Vec3 average = ContactPlan.riflemanAveragePosition(soldier);
        BlockPos target = soldier.getAttackTargetPos();
        if (average == null || target == null) return spine.pointAt(endProgress, y);
        Vec3 axis = Vec3.atCenterOf(target).subtract(average);
        axis = new Vec3(axis.x, 0.0, axis.z);
        if (axis.lengthSqr() < 1.0E-6) return spine.pointAt(endProgress, y);
        axis = axis.normalize();
        for (double progress = endProgress; progress >= fromProgress; progress -= 1.0) {
            BlockPos candidate = spine.pointAt(progress, y);
            double along = progress(Vec3.atCenterOf(candidate), average, axis);
            if (along <= -REAR_OFFSET
                && !aheadOfRifleLine(soldier, target, candidate)) {
                return candidate;
            }
        }
        return soldier.blockPosition();
    }
}
