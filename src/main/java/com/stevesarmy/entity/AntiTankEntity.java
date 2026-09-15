package com.stevesarmy.entity;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;

/**
 * Anti-tank role. Uses the rifleman pipeline: identical goal layout, state
 * machine, ping handling, and movement. AT-specific behavior is the launcher
 * policy inside {@link com.stevesarmy.entity.ai.SoldierCombatGoal} — the AT
 * gun is raised only while a vehicle contact owns the engagement and is never
 * fired at infantry. The soldier is the squad's designated armor hunter only
 * while an AT gun (see {@code atGunIdPatterns}) is somewhere in his inventory.
 */
public class AntiTankEntity extends SoldierEntity {

    public AntiTankEntity(EntityType<? extends SoldierEntity> type, Level level) {
        super(type, level);
    }

    @Override
    public SoldierRole getRole() {
        return SoldierRole.ANTI_TANK;
    }
}
