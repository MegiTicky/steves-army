package com.stevesarmy.compat.ysm;

import com.elfmcys.yesstevemodel.client.animation.IAnimationPredicate;
import com.elfmcys.yesstevemodel.client.compat.gun.tacz.TacCompat;
import com.elfmcys.yesstevemodel.client.compat.gun.swarfare.SWarfareCompat;
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
            return playState(event, "death", ILoopType.EDefaultLoopTypes.PLAY_ONCE);
        }
        if (entity.isAutoSpinAttack()) {
            return playState(event, "riptide", ILoopType.EDefaultLoopTypes.LOOP);
        }
        if (entity.getPose() == Pose.SLEEPING) {
            return playState(event, "sleep", ILoopType.EDefaultLoopTypes.LOOP);
        }
        if (entity.isSwimming()) {
            return playState(event, "swim", ILoopType.EDefaultLoopTypes.LOOP);
        }
        if (entity.getPose() == Pose.SWIMMING && Math.abs(event.getLimbSwingAmount()) > MIN_SPEED) {
            return playState(event, "climb", ILoopType.EDefaultLoopTypes.LOOP);
        }
        if (entity.getPose() == Pose.SWIMMING) {
            return playState(event, "climbing", ILoopType.EDefaultLoopTypes.LOOP);
        }
        float verticalSpeed = getVerticalSpeed(entity);
        if (entity.onClimbable() && verticalSpeed > 0.0f) {
            return playState(event, "ladder_up", ILoopType.EDefaultLoopTypes.LOOP);
        }
        if (entity.onClimbable() && verticalSpeed == 0.0f) {
            return playState(event, "ladder_stillness", ILoopType.EDefaultLoopTypes.LOOP);
        }
        if (entity.onClimbable() && verticalSpeed < 0.0f) {
            return playState(event, "ladder_down", ILoopType.EDefaultLoopTypes.LOOP);
        }
        if (entity.getPose() == Pose.FALL_FLYING && entity.isFallFlying()) {
            return playState(event, "elytra_fly", ILoopType.EDefaultLoopTypes.LOOP);
        }
        if (entity.isInWater() && !entity.onGround()) {
            return playState(event, "swim_stand", ILoopType.EDefaultLoopTypes.LOOP);
        }
        if (entity.hurtTime > 0) {
            return playState(event, "attacked", ILoopType.EDefaultLoopTypes.PLAY_ONCE);
        }
        if (!entity.onGround() && !entity.isInWater()) {
            return playState(event, "jump", ILoopType.EDefaultLoopTypes.LOOP);
        }
        if (entity.onGround() && entity.getPose() == Pose.CROUCHING && Math.abs(event.getLimbSwingAmount()) > MIN_SPEED) {
            return playState(event, "sneak", ILoopType.EDefaultLoopTypes.LOOP);
        }
        if (entity.onGround() && entity.getPose() == Pose.CROUCHING) {
            return playState(event, "sneaking", ILoopType.EDefaultLoopTypes.LOOP);
        }
        if (entity.onGround() && entity.isSprinting()) {
            return playState(event, "run", ILoopType.EDefaultLoopTypes.LOOP);
        }
        if (entity.onGround() && event.getLimbSwingAmount() > MIN_SPEED) {
            return playState(event, "walk", ILoopType.EDefaultLoopTypes.LOOP);
        }
        return playState(event, "idle", ILoopType.EDefaultLoopTypes.LOOP);
    }

    private static PlayState playState(AnimationEvent<SoldierModelCapability> event, String name, ILoopType loopType) {
        LivingEntity entity = event.getAnimatable().getEntity();
        PlayState taczState = TacCompat.handleTaczAnimState(entity, event, name, loopType);
        if (taczState != null) {
            return taczState;
        }
        PlayState warfareState = SWarfareCompat.handleTaczAnim(entity, event, name, loopType);
        if (warfareState != null) {
            return warfareState;
        }
        return IAnimationPredicate.playAnimationWithLoop(event, name, loopType);
    }

    private static float getVerticalSpeed(LivingEntity entity) {
        return 20.0f * ((float) (entity.position().y - entity.yo));
    }
}
