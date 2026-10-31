package com.stevesarmy.entity.ai;

import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.vehicle.VehicleBoardingManager;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;

/** Gives command-mounted soldiers exclusive control of their walking until they board. */
public class VehicleBoardingGoal extends Goal {
    private final SoldierEntity soldier;

    public VehicleBoardingGoal(SoldierEntity soldier) {
        this.soldier = soldier;
        setFlags(EnumSet.of(Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        return VehicleBoardingManager.isBoarding(soldier);
    }

    @Override
    public boolean canContinueToUse() {
        return VehicleBoardingManager.isBoarding(soldier);
    }

    @Override
    public void tick() {
        VehicleBoardingManager.tick(soldier);
    }

    @Override
    public void stop() {
        if (!VehicleBoardingManager.isBoarding(soldier)) {
            soldier.getNavigation().stop();
        }
    }
}
