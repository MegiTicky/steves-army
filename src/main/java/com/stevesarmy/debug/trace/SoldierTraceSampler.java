package com.stevesarmy.debug.trace;

import com.google.gson.*;
import com.stevesarmy.GameClock;
import com.stevesarmy.combat.CombatStateSnapshot;
import com.stevesarmy.combat.GunIntegration;
import com.stevesarmy.combat.cover.CoverReservationManager;
import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.entity.ai.*;
import com.stevesarmy.squad.ClearingCoordinator;
import com.stevesarmy.squad.SquadManager;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;

/** Observations only: never asks a controller to decide, search, trace rays or compute a route. */
public final class SoldierTraceSampler {
    private SoldierTraceSampler() {}

    public static CombatStateSnapshot state(SoldierEntity soldier) {
        CoverGoalController controller = soldier.getCoverTacticalGoal();
        if (controller instanceof CoverTacticalGoal goal) return goal.captureStateSnapshot();
        if (controller instanceof MachineGunnerSupportGoal goal) return goal.getCoverController().captureStateSnapshot();
        if (controller instanceof SupportCoverGoal goal) return goal.getCoverController().captureStateSnapshot();
        return null;
    }

    public static JsonObject identity(Entity entity) {
        JsonObject j = new JsonObject();
        j.addProperty("id", entity.getUUID().toString());
        j.addProperty("entityId", entity.getId());
        j.addProperty("name", entity.getName().getString());
        j.addProperty("type", BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString());
        j.addProperty("dimension", entity.level().dimension().location().toString());
        if (entity instanceof SoldierEntity s) {
            j.addProperty("role", s.getRole().name());
            j.addProperty("ownerId", s.getOwnerUUID().map(Object::toString).orElse(null));
            j.addProperty("squadId", s.getSquadId() == null ? null : s.getSquadId().toString());
            JsonObject equipment = new JsonObject();
            for (EquipmentSlot slot : EquipmentSlot.values()) {
                ItemStack stack = s.getItemBySlot(slot);
                equipment.addProperty(slot.getName(), stack.save(new net.minecraft.nbt.CompoundTag()).toString());
            }
            j.add("equipment", equipment);
        }
        return j;
    }

    public static JsonObject frame(SoldierEntity s, CombatStateSnapshot state, Vec3 previous, int pathId) {
        JsonObject j = new JsonObject();
        j.add("position", TraceJson.vector(s.position()));
        j.add("velocity", TraceJson.vector(s.getDeltaMovement()));
        j.add("displacement", TraceJson.vector(previous == null ? Vec3.ZERO : s.position().subtract(previous)));
        j.addProperty("health", s.getHealth());
        j.addProperty("alive", s.isAlive());
        j.addProperty("noAi", s.isNoAi());
        j.addProperty("onGround", s.onGround());
        j.addProperty("horizontalCollision", s.horizontalCollision);
        j.addProperty("verticalCollision", s.verticalCollision);
        j.addProperty("posture", s.getPose().name());
        j.addProperty("healing", s.isHealing());
        j.addProperty("reloading", s.isPreparingOrReloading());
        j.addProperty("vehicleCrew", s.isVehicleCrewActive());
        j.addProperty("vehicleId", s.getVehicle() == null ? null : s.getVehicle().getUUID().toString());
        j.add("runningGoals", TraceJson.GSON.toJsonTree(s.getTraceRunningGoals()));
        j.add("state", state == null ? JsonNull.INSTANCE : TraceJson.GSON.toJsonTree(state));
        j.addProperty("stateAvailable", state != null);
        JsonObject movement = new JsonObject();
        if (s.getMoveControl() instanceof CoverPositionController c) {
            movement.addProperty("source", c.debugMoveSource());
            movement.addProperty("reason", c.debugMoveReason());
            movement.addProperty("cautionReason", c.cautionReason());
            movement.addProperty("result", c.getLastResult().name());
            movement.addProperty("failure", c.getLastFailureReason().name());
            movement.add("target", TraceJson.vector(c.getDebugTargetPos()));
            movement.addProperty("tolerance", c.getDebugTolerance());
            movement.addProperty("anchorPulls", c.getDebugAnchorPulls());
            movement.add("speed", TraceJson.GSON.toJsonTree(c.getSpeedController().snapshot()));
        }
        movement.addProperty("attributeSpeed", s.getAttributeValue(Attributes.MOVEMENT_SPEED));
        movement.add("policy", TraceJson.GSON.toJsonTree(s.getMovementController().snapshot()));
        movement.addProperty("traversalLocked", s.isNavigationTraversalLocked());
        movement.addProperty("traversalLockReason", s.getNavigationTraversalLockReason());
        j.add("movement", movement);
        j.add("navigation", navigation(s, pathId, false));
        JsonObject orders = new JsonObject();
        orders.addProperty("mode", s.getSquadMode().name());
        orders.addProperty("fireTeam", s.getFireTeam().name());
        orders.addProperty("fireDiscipline", s.getFireDiscipline().name());
        orders.addProperty("goToHolding", s.isGoToHolding());
        orders.addProperty("goToGeneration", s.getPingMoveGeneration());
        orders.addProperty("attackGeneration", s.getAttackGeneration());
        orders.add("goTo", TraceJson.GSON.toJsonTree(s.getPingMoveTarget()));
        orders.add("attack", TraceJson.GSON.toJsonTree(s.getAttackTargetPos()));
        orders.add("hold", TraceJson.GSON.toJsonTree(s.getHoldPosition()));
        orders.add("suppression", TraceJson.GSON.toJsonTree(s.getPingSuppressPos()));
        orders.add("threatPing", TraceJson.GSON.toJsonTree(s.getPingThreatPos()));
        orders.add("forcedTarget", TraceJson.GSON.toJsonTree(s.getForcedTargetPos()));
        j.add("orders", orders);
        j.addProperty("targetId", s.getTarget() == null ? null : s.getTarget().getUUID().toString());
        JsonArray threats = new JsonArray();
        for (var threat : s.getThreatAwareness().getThreatInfos()) {
            JsonObject t = new JsonObject();
            t.add("position", TraceJson.GSON.toJsonTree(threat.position));
            t.addProperty("id", threat.entity == null ? null : threat.entity.getUUID().toString());
            t.addProperty("weight", threat.weight);
            t.addProperty("observationAgeMs", threat.lastObservedTime < 0 ? -1 : GameClock.nowMs() - threat.lastObservedTime);
            threats.add(t);
        }
        j.add("threats", threats);
        j.addProperty("ammo", GunIntegration.hasGun(s) ? GunIntegration.getCurrentAmmo(s) : -1);
        j.addProperty("magazineSize", GunIntegration.hasGun(s) ? GunIntegration.getMagazineSize(s) : -1);
        j.addProperty("reservationCount", state != null && state.currentCover != null
            ? CoverReservationManager.getReservationCount(state.currentCover) : -1);
        if (state != null && state.currentCover != null) {
            j.addProperty("slotOffset", s.position().subtract(CoverTacticalGoal.getCoverStandingPositionStatic(state.currentCover)).horizontalDistance());
        }
        if (s.level() instanceof ServerLevel level && s.getSquadId() != null) {
            SquadManager.get(level).getSquadById(s.getSquadId()).ifPresent(squad -> {
                var cell = squad.getDangerMap().cellAt(s.getX(), s.getZ(), level.getGameTime());
                if (cell != null) {
                    j.addProperty("dangerScore", cell.score);
                    j.addProperty("dangerLegExposed", cell.legExposed);
                }
            });
        }
        var clearing = ClearingCoordinator.viewFor(s);
        if (clearing != null) {
            JsonObject c = new JsonObject();
            c.addProperty("stage", clearing.stage().name());
            c.addProperty("checked", clearing.checkedCount());
            c.addProperty("secureAssemble", clearing.secureAssemble());
            c.addProperty("advancing", s.getCoverTacticalGoal().isClearingAdvancing());
            j.add("clearing", c);
        }
        return j;
    }

    public static JsonObject navigation(SoldierEntity s, int id, boolean geometry) {
        JsonObject j = new JsonObject();
        Path p = s.getNavigation().getPath();
        j.addProperty("pathId", id);
        j.addProperty("done", s.getNavigation().isDone());
        j.addProperty("nodeCount", p == null ? 0 : p.getNodeCount());
        j.addProperty("nextNodeIndex", p == null ? -1 : p.getNextNodeIndex());
        j.addProperty("canReach", p != null && p.canReach());
        j.add("target", p == null ? JsonNull.INSTANCE : TraceJson.GSON.toJsonTree(p.getTarget()));
        if (s.getNavigation() instanceof SoldierGroundNavigation nav) j.addProperty("speedModifier", nav.getNavSpeedModifier());
        if (geometry && p != null) {
            JsonArray nodes = new JsonArray();
            for (int i = 0; i < p.getNodeCount(); i++) nodes.add(TraceJson.GSON.toJsonTree(p.getNode(i).asBlockPos()));
            j.add("path", nodes);
        }
        return j;
    }
}
