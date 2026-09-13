package com.stevesarmy.entity;

import com.stevesarmy.entity.ai.VehicleCrewGoal;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;

/**
 * Enemy vehicle crew: an enemy soldier that mans tallyho hull MGs and
 * periscopes, giving squad AI a live hostile vehicle to react to. Inherits
 * the enemy soldier's goals (cover, combat, defend spawn) and adds the
 * type-agnostic crew goal — {@link VehicleCrewGoal} claims the nearest free
 * station within station reach and {@code StationGunnerAI} (whose only gate
 * is the role) runs the gun remotely while the soldier stands its ground.
 * Hostile to everything except other enemy soldiers, exactly like infantry.
 */
public class EnemyVehicleCrewEntity extends EnemySoldierEntity {

    public EnemyVehicleCrewEntity(EntityType<? extends SoldierEntity> type, Level level) {
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
