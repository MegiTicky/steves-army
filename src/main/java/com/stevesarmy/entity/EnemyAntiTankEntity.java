package com.stevesarmy.entity;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;

/**
 * Enemy anti-tank soldier. Inherits the enemy soldier's goals and behavior;
 * the AT role makes him his squad's designated armor hunter, so player
 * vehicles are engaged through the vehicle branch of the shared combat goal
 * exactly like friendly AT soldiers engage enemy vehicles. Hostile to
 * everything except other enemy soldiers, exactly like infantry.
 */
public class EnemyAntiTankEntity extends EnemySoldierEntity {

    public EnemyAntiTankEntity(EntityType<? extends SoldierEntity> type, Level level) {
        super(type, level);
    }

    @Override
    public SoldierRole getRole() {
        return SoldierRole.ANTI_TANK;
    }
}
