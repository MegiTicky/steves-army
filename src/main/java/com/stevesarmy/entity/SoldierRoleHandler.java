package com.stevesarmy.entity;

import com.stevesarmy.inventory.SoldierInventory;
import com.stevesarmy.registry.ModEntities;
import com.stevesarmy.respawn.PlayerDeathHandler;
import com.stevesarmy.squad.FireTeamAssignment;
import com.stevesarmy.squad.OwnedSoldierRegistry;
import com.stevesarmy.squad.SquadManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;

import java.util.UUID;

/** Converts a soldier between roles by swapping the entity type while preserving
 *  all persistent state and squad registrations. Free and instant by design. */
public final class SoldierRoleHandler {
    private SoldierRoleHandler() {}

    public static EntityType<? extends SoldierEntity> getEntityType(SoldierRole role) {
        return switch (role) {
            case RIFLEMAN -> ModEntities.SOLDIER.get();
            case MACHINE_GUNNER -> ModEntities.MACHINE_GUNNER.get();
        };
    }

    /** Returns true when the conversion was performed. */
    public static boolean convertSoldier(SoldierEntity soldier, SoldierRole targetRole) {
        if (soldier.level().isClientSide) {
            return false;
        }
        if (soldier.getRole() == targetRole) {
            return false;
        }
        if (!soldier.isAlive() || soldier.isRemoved()) {
            return false;
        }
        if (soldier.getVehicle() != null || soldier.isPassenger()) {
            return false;
        }
        if (soldier.isRecalling()) {
            return false;
        }
        if (PlayerDeathHandler.isPendingRespawnTarget(soldier.getUUID())) {
            return false;
        }

        ServerLevel level = (ServerLevel) soldier.level();
        SoldierEntity replacement = getEntityType(targetRole).create(level);
        if (replacement == null) {
            return false;
        }

        // Persistent data copy. Must precede addFreshEntity: the team join
        // handler generates names when absent and reads the fire team assignment.
        replacement.moveTo(soldier.getX(), soldier.getY(), soldier.getZ(), soldier.getYRot(), soldier.getXRot());
        replacement.setYHeadRot(soldier.getYHeadRot());
        replacement.setHealth(soldier.getHealth());

        SoldierInventory src = soldier.getSoldierInventory();
        SoldierInventory dst = replacement.getSoldierInventory();
        for (int i = 0; i < src.getContainerSize(); i++) {
            dst.setItem(i, src.getItem(i).copy());
        }
        dst.syncArmorToEntity(replacement);

        soldier.getOwnerUUID().ifPresent(replacement::setOwnerUUID);
        replacement.setFollowState(soldier.getFollowState());
        replacement.setSquadMode(soldier.getSquadMode());
        replacement.setFireDiscipline(soldier.getFireDiscipline());
        replacement.setFireTeam(soldier.getFireTeam());
        replacement.setHoldPosition(soldier.getHoldPosition());
        replacement.setSquadFormation(soldier.getSquadFormation());
        BlockPos formationOffset = soldier.getFormationOffset();
        if (formationOffset != null) {
            replacement.setFormationOffset(formationOffset);
        }
        if (soldier.hasCustomName()) {
            replacement.setCustomName(soldier.getCustomName());
            replacement.setCustomNameVisible(soldier.isCustomNameVisible());
        }
        replacement.setPersistenceRequired();

        UUID oldUuid = soldier.getUUID();
        UUID squadId = soldier.getSquadId();
        replacement.setSquadId(squadId);

        UUID ownerUuid = soldier.getOwnerUUID().orElse(null);
        if (ownerUuid != null) {
            FireTeamAssignment.get(level, ownerUuid)
                .assignToTeam(replacement.getUUID(), replacement.getFireTeam());
        }

        level.addFreshEntity(replacement);

        if (squadId != null) {
            SquadManager.get(level).addMemberToSquad(squadId, replacement.getUUID());
        }

        OwnedSoldierRegistry registry = OwnedSoldierRegistry.get(level.getServer());
        registry.remove(oldUuid);
        if (ownerUuid != null) {
            FireTeamAssignment.get(level, ownerUuid).removeSoldier(oldUuid);
        }

        soldier.discard();
        return true;
    }
}
