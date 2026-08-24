package com.stevesarmy.entity;

import com.stevesarmy.ping.PingType;
import com.stevesarmy.squad.SquadMode;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.OpenDoorGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

/**
 * Player-owned stationary defender. Holds its spawn post like an enemy soldier
 * (patrols a small radius, seeks cover, fights automatically) but is friendly:
 * it joins the owner's squad and friendly team, yet never responds to pings and
 * is never part of the A/B/C/D maneuver fire teams.
 */
public class GarrisonEntity extends SoldierEntity {

    public GarrisonEntity(EntityType<? extends SoldierEntity> type, Level level) {
        super(type, level);
        this.setSquadMode(SquadMode.HOLD);
    }

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(1, new OpenDoorGoal(this, true));
        this.goalSelector.addGoal(2, initializeCoverTacticalGoal());
        this.goalSelector.addGoal(2, initializeCombatGoal());
        this.goalSelector.addGoal(3, new com.stevesarmy.entity.ai.DefendPositionGoal(this));
        this.goalSelector.addGoal(4, new LookAtPlayerGoal(this, Player.class, 8.0F));
        this.goalSelector.addGoal(5, new RandomLookAroundGoal(this));
    }

    @Override
    public void tick() {
        super.tick();

        if (!this.level().isClientSide && defendPosition == null) {
            defendPosition = this.blockPosition();
        }
    }

    @Override
    public SoldierRole getRole() {
        return SoldierRole.GARRISON;
    }

    /** Garrisons never respond to any ping. */
    @Override
    public void receivePing(PingType type, net.minecraft.world.phys.Vec3 position) {
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        if (defendPosition != null) {
            tag.putLong("DefendPosition", defendPosition.asLong());
        }
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.contains("DefendPosition")) {
            defendPosition = BlockPos.of(tag.getLong("DefendPosition"));
        }
    }
}
