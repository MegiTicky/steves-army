package com.stevesarmy.entity.ai;

import com.stevesarmy.entity.EnemySoldierEntity;
import com.stevesarmy.entity.SoldierEntity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.Comparator;
import java.util.List;

public class TargetPlayerSoldierGoal extends Goal {

    private final EnemySoldierEntity enemy;
    private SoldierEntity target;

    public TargetPlayerSoldierGoal(EnemySoldierEntity enemy) {
        this.enemy = enemy;
    }

    @Override
    public boolean canUse() {
        List<SoldierEntity> nearby = enemy.level().getEntitiesOfClass(
            SoldierEntity.class,
            enemy.getBoundingBox().inflate(30.0),
            soldier -> !(soldier instanceof EnemySoldierEntity)
                && soldier.getOwnerUUID().isPresent()
                && soldier.isAlive()
        );

        if (nearby.isEmpty()) return false;

        target = nearby.stream()
            .min(Comparator.comparingDouble(e -> e.distanceToSqr(enemy)))
            .orElse(null);

        return target != null;
    }

    @Override
    public boolean canContinueToUse() {
        return target != null && target.isAlive() && enemy.distanceToSqr(target) < 900.0;
    }

    @Override
    public void start() {
        if (target != null) {
            enemy.setTarget(target);
        }
    }

    @Override
    public void stop() {
        this.target = null;
    }
}