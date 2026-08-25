package com.stevesarmy.compat.ysm;

import com.elfmcys.yesstevemodel.client.animation.IAnimationPredicate;
import com.elfmcys.yesstevemodel.geckolib3.core.builder.ILoopType;
import com.elfmcys.yesstevemodel.geckolib3.core.event.predicate.AnimationEvent;
import com.elfmcys.yesstevemodel.geckolib3.core.enums.PlayState;
import com.elfmcys.yesstevemodel.molang.runtime.ExpressionEvaluator;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * Main ("player.main") animation predicate for soldiers. Mirrors the states registered by
 * OpenYSM's {@code AnimationRegister} for players, but operates on {@link LivingEntity}
 * only, so soldier entities are safe.
 */
@OnlyIn(Dist.CLIENT)
public class SoldierMainAnimationPredicate implements IAnimationPredicate<SoldierModelCapability> {

    private static final float MIN_SPEED = 0.05f;

    @Override
    public PlayState predicate(AnimationEvent<SoldierModelCapability> event, ExpressionEvaluator<?> evaluator) {
        LivingEntity entity = event.getAnimatable().getEntity();
        if (entity == null) {
            return PlayState.STOP;
        }
        if (entity.isDeadOrDying()) {
            return playOnce(event, "death");
        }
        if (entity.isAutoSpinAttack()) {
            return loop(event, "riptide");
        }
        if (entity.getPose() == Pose.SLEEPING) {
            return loop(event, "sleep");
        }
        if (entity.isSwimming()) {
            return loop(event, "swim");
        }
        if (entity.getPose() == Pose.SWIMMING && Math.abs(event.getLimbSwingAmount()) > MIN_SPEED) {
            return loop(event, "climb");
        }
        if (entity.getPose() == Pose.SWIMMING) {
            return loop(event, "climbing");
        }
        float verticalSpeed = getVerticalSpeed(entity);
        if (entity.onClimbable() && verticalSpeed > 0.0f) {
            return loop(event, "ladder_up");
        }
        if (entity.onClimbable() && verticalSpeed == 0.0f) {
            return loop(event, "ladder_stillness");
        }
        if (entity.onClimbable() && verticalSpeed < 0.0f) {
            return loop(event, "ladder_down");
        }
        if (entity.getPose() == Pose.FALL_FLYING && entity.isFallFlying()) {
            return loop(event, "elytra_fly");
        }
        if (entity.isInWater() && !entity.onGround()) {
            return loop(event, "swim_stand");
        }
        if (entity.hurtTime > 0) {
            return playOnce(event, "attacked");
        }
        if (!entity.onGround() && !entity.isInWater()) {
            return loop(event, "jump");
        }
        if (entity.onGround() && entity.getPose() == Pose.CROUCHING && Math.abs(event.getLimbSwingAmount()) > MIN_SPEED) {
            return loop(event, "sneak");
        }
        if (entity.onGround() && entity.getPose() == Pose.CROUCHING) {
            return loop(event, "sneaking");
        }
        if (entity.onGround() && entity.isSprinting()) {
            return loop(event, "run");
        }
        if (entity.onGround() && event.getLimbSwingAmount() > MIN_SPEED) {
            return loop(event, "walk");
        }
        return loop(event, "idle");
    }

    private static PlayState loop(AnimationEvent<SoldierModelCapability> event, String name) {
        return IAnimationPredicate.playAnimationWithLoop(event, name, ILoopType.EDefaultLoopTypes.LOOP);
    }

    private static PlayState playOnce(AnimationEvent<SoldierModelCapability> event, String name) {
        return IAnimationPredicate.playAnimationWithLoop(event, name, ILoopType.EDefaultLoopTypes.PLAY_ONCE);
    }

    private static float getVerticalSpeed(LivingEntity entity) {
        return 20.0f * ((float) (entity.position().y - entity.yo));
    }
}
