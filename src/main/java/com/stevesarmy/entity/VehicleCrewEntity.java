package com.stevesarmy.entity;

import com.stevesarmy.entity.ai.VehicleCrewGoal;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;

/**
 * Vehicle crew role. Seeks the nearest free Create seat on a VS2 ship and stays
 * there, manning tallyho hull MGs (fire) or periscopes (scouting) when present.
 * All station access is reflection-based; without tallyho the crew seats and
 * stays. Uses the rifleman goal layout plus the crew goal — while unseated the
 * soldier behaves like a normal infantryman and fights with its own weapon.
 */
public class VehicleCrewEntity extends SoldierEntity {

    public VehicleCrewEntity(EntityType<? extends SoldierEntity> type, Level level) {
        super(type, level);
    }

    @Override
    public SoldierRole getRole() {
        return SoldierRole.VEHICLE_CREW;
    }

    @Override
    protected void registerGoals() {
        super.registerGoals();
        this.goalSelector.addGoal(1, new VehicleCrewGoal(this));
    }
}
