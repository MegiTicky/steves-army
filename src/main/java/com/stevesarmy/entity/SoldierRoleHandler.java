package com.stevesarmy.entity;

import com.stevesarmy.inventory.SoldierInventory;
import com.stevesarmy.registry.ModEntities;
import com.stevesarmy.respawn.PlayerDeathHandler;
import com.stevesarmy.squad.FireTeam;
import com.stevesarmy.squad.FireTeamAssignment;
import com.stevesarmy.squad.OwnedSoldierRegistry;
import com.stevesarmy.squad.SquadManager;
import com.stevesarmy.squad.SquadMode;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;

import javax.annotation.Nullable;
import java.util.UUID;

/** Converts a soldier between roles by swapping the entity type while preserving
 *  all persistent state and squad registrations. Free and instant by design. */
public final class SoldierRoleHandler {
    private SoldierRoleHandler() {}

    public static EntityType<? extends SoldierEntity> getEntityType(SoldierRole role) {
        return switch (role) {
            case RIFLEMAN -> ModEntities.SOLDIER.get();
            case MACHINE_GUNNER -> ModEntities.MACHINE_GUNNER.get();
            case GARRISON -> ModEntities.GARRISON.get();
            case SUPPORT -> ModEntities.SUPPORT.get();
        };
    }

    /** Returns the replacement soldier when the conversion was performed. */
    public static SoldierEntity convertSoldier(SoldierEntity soldier, SoldierRole targetRole) {
        return convertSoldier(soldier, targetRole, null);
    }

    /**
     * Converts a soldier to a new role. When {@code targetFireTeam} is provided the
     * replacement is assigned to that fire team; a garrison conversion always forces
     * the GARRISON team and is never stored in {@link FireTeamAssignment}.
     *
     * @return the replacement entity, or {@code null} when the conversion was refused.
     */
    @Nullable
    public static SoldierEntity convertSoldier(SoldierEntity soldier, SoldierRole targetRole,
                                               @Nullable FireTeam targetFireTeam) {
        if (soldier.level().isClientSide) {
            return null;
        }
        if (soldier.getRole() == targetRole) {
            return null;
        }
        if (!soldier.isAlive() || soldier.isRemoved()) {
            return null;
        }
        if (soldier.getVehicle() != null || soldier.isPassenger()) {
            return null;
        }
        if (soldier.isRecalling()) {
            return null;
        }
        if (PlayerDeathHandler.isPendingRespawnTarget(soldier.getUUID())) {
            return null;
        }
        if (soldier instanceof TeamGarrisonEntity) {
            return null;
        }

        boolean sourceIsGarrison = soldier.getFireTeam() == FireTeam.GARRISON;
        FireTeam assignedTeam = targetFireTeam;
        if (targetRole == SoldierRole.GARRISON) {
            assignedTeam = FireTeam.GARRISON;
        } else if (sourceIsGarrison && assignedTeam == null) {
            UUID ownerUuid = soldier.getOwnerUUID().orElse(null);
            if (ownerUuid != null) {
                assignedTeam = FireTeamAssignment.get((ServerLevel) soldier.level(), ownerUuid)
                    .getActiveTeams().get(0);
            }
        }

        ServerLevel level = (ServerLevel) soldier.level();
        SoldierEntity replacement = getEntityType(targetRole).create(level);
        if (replacement == null) {
            return null;
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

        if (targetRole == SoldierRole.GARRISON) {
            replacement.setFireTeam(FireTeam.GARRISON);
            replacement.setSquadMode(SquadMode.HOLD);
            replacement.setHoldPosition(soldier.blockPosition());
            if (replacement instanceof GarrisonEntity garrison) {
                garrison.setDefendPosition(soldier.blockPosition());
            }
        } else {
            if (assignedTeam != null) {
                replacement.setFireTeam(assignedTeam);
            }
            if (sourceIsGarrison) {
                replacement.setSquadMode(SquadMode.FOLLOW);
            }
        }

        UUID ownerUuid = soldier.getOwnerUUID().orElse(null);
        if (ownerUuid != null) {
            FireTeamAssignment assignment = FireTeamAssignment.get(level, ownerUuid);
            if (replacement.getFireTeam() != FireTeam.GARRISON) {
                assignment.assignToTeam(replacement.getUUID(), replacement.getFireTeam());
            }
            assignment.removeSoldier(oldUuid);
        }

        level.addFreshEntity(replacement);

        if (squadId != null) {
            SquadManager.get(level).addMemberToSquad(squadId, replacement.getUUID());
        }

        OwnedSoldierRegistry registry = OwnedSoldierRegistry.get(level.getServer());
        registry.remove(oldUuid);

        soldier.discard();
        return replacement;
    }
}
