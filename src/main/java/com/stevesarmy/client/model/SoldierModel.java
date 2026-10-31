package com.stevesarmy.client.model;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.stevesarmy.client.animation.SoldierAnimations;
import com.stevesarmy.client.animation.ReviewedSoldierPoses;
import com.stevesarmy.combat.GunIntegration;
import com.stevesarmy.entity.SoldierEntity;
import net.minecraft.client.animation.AnimationDefinition;
import net.minecraft.client.animation.KeyframeAnimations;
import net.minecraft.client.model.HierarchicalModel;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.*;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.HumanoidArm;
import org.joml.Vector3f;
import org.joml.Quaternionf;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Rig and weapon poses adapted from nekoyuni's Simple Enemy Mod (GPL-3.0),
 * UnitModelDefinitions and procedural animation layers. Uses our player skins.
 * Split legs add kneeling without shortening the original twelve-pixel limbs.
 */
public class SoldierModel<T extends SoldierEntity> extends HumanoidModel<T> {
    private final ModelPart unit, rightShin, leftShin;
    private final HierarchicalModel<T> animationModel;
    private final Vector3f scratch = new Vector3f();
    private final Map<T, Motion> motions = new WeakHashMap<>();
    private float weaponReady;
    private Quaternionf reviewedWeaponRotation;
    private float reviewedWeaponWeight;

    public SoldierModel(ModelPart root) {
        super(root);
        unit = root;
        rightShin = rightLeg.getChild("lower_leg");
        leftShin = leftLeg.getChild("lower_leg");
        ModelPart aliases = new ModelPart(List.of(), Map.of("head", head, "body", body,
            "rightArm", rightArm, "leftArm", leftArm, "rightLeg", rightLeg, "leftLeg", leftLeg));
        ModelPart animationRoot = new ModelPart(List.of(), Map.of("unit", unit));
        animationModel = new HierarchicalModel<>() {
            @Override public ModelPart root() { return animationRoot; }
            @Override public java.util.Optional<ModelPart> getAnyDescendantWithName(String name) {
                if (name.equals("unit")) return java.util.Optional.of(unit);
                return aliases.hasChild(name) ? java.util.Optional.of(aliases.getChild(name)) : java.util.Optional.empty();
            }
            @Override public void setupAnim(T e, float a, float b, float c, float d, float f) { }
        };
    }

    public static LayerDefinition createBodyLayer() { return createLayer(CubeDeformation.NONE, false); }

    public static LayerDefinition createBodyLayer(CubeDeformation d) { return createLayer(d, true); }

    private static LayerDefinition createLayer(CubeDeformation d, boolean armor) {
        MeshDefinition mesh = new MeshDefinition();
        PartDefinition root = mesh.getRoot();
        root.addOrReplaceChild("head", CubeListBuilder.create().texOffs(0, 0)
            .addBox(-4, -8, -4, 8, 8, 8, d), PartPose.offsetAndRotation(0, -6.16F, 1.0616F, -.0105F, .0735F, .0228F));
        root.addOrReplaceChild("hat", CubeListBuilder.create().texOffs(32, 0)
            .addBox(-4, -8, -4, 8, 8, 8, d.extend(.5F)), PartPose.ZERO);
        root.addOrReplaceChild("body", CubeListBuilder.create().texOffs(16, 16)
            .addBox(-4, 0, -2, 8, 12, 4, d), PartPose.offsetAndRotation(0, -6.26F, 1.0616F, .0997F, .6536F, .0238F));
        root.addOrReplaceChild("right_arm", CubeListBuilder.create().texOffs(40, 16)
            .addBox(-3.0123F, -1.9895F, -1.7505F, 4, 12, 4, d),
            PartPose.offsetAndRotation(-3, -2.9089F, 3.4377F, -1.6919F, -.0024F, -.0561F));
        root.addOrReplaceChild("left_arm", CubeListBuilder.create().texOffs(armor ? 40 : 32, armor ? 16 : 48).mirror(armor)
            .addBox(-1, -2, -2, 4, 12, 4, d),
            PartPose.offsetAndRotation(2.5F, -3.2588F, -3.4123F, -1.5619F, .6592F, .1584F));
        addLeg(root, "right_leg", 0, 16, -1.9F, 6, 4, .1309F, d);
        addLeg(root, "left_leg", armor ? 0 : 16, armor ? 16 : 48, 2.15F, 5.85F, -1, -.1745F, d);
        if (!armor) {
            root.getChild("body").addOrReplaceChild("jacket", CubeListBuilder.create().texOffs(16, 32)
                .addBox(-4, 0, -2, 8, 12, 4, d.extend(.25F)), PartPose.ZERO);
            root.getChild("right_arm").addOrReplaceChild("sleeve", CubeListBuilder.create().texOffs(40, 32)
                .addBox(-3.0123F, -1.9895F, -1.7505F, 4, 12, 4, d.extend(.25F)), PartPose.ZERO);
            root.getChild("left_arm").addOrReplaceChild("sleeve", CubeListBuilder.create().texOffs(48, 48)
                .addBox(-1, -2, -2, 4, 12, 4, d.extend(.25F)), PartPose.ZERO);
            addPants(root.getChild("right_leg"), 0, 32);
            addPants(root.getChild("left_leg"), 0, 48);
        }
        return LayerDefinition.create(mesh, 64, armor ? 32 : 64);
    }

    private static void addLeg(PartDefinition root, String name, int u, int v,
                               float x, float y, float z, float pitch, CubeDeformation d) {
        PartDefinition thigh = root.addOrReplaceChild(name, CubeListBuilder.create().texOffs(u, v)
            .addBox(-2, 0, -2, 4, 6, 4, d), PartPose.offsetAndRotation(x, y, z, pitch, 0, 0));
        thigh.addOrReplaceChild("lower_leg", CubeListBuilder.create().texOffs(u, v + 6)
            .addBox(-2, 0, -2, 4, 6, 4, d), PartPose.offset(0, 6, 0));
    }

    private static void addPants(PartDefinition thigh, int u, int v) {
        CubeDeformation d = new CubeDeformation(.25F);
        thigh.addOrReplaceChild("pants", CubeListBuilder.create().texOffs(u, v)
            .addBox(-2, 0, -2, 4, 6, 4, d), PartPose.ZERO);
        thigh.getChild("lower_leg").addOrReplaceChild("pants", CubeListBuilder.create().texOffs(u, v + 6)
            .addBox(-2, 0, -2, 4, 6, 4, d), PartPose.ZERO);
    }

    @Override
    public void setupAnim(T entity, float swing, float speed, float age, float yaw, float pitch) {
        // HumanoidModel.setupAnim has TaCZ injections: don't apply a second arm pose.
        unit.getAllParts().forEach(ModelPart::resetPose);
        reviewedWeaponRotation = null;
        unit.x = 0; unit.y = 6; unit.z = -1; unit.xRot = 5 * Mth.DEG_TO_RAD;
        unit.yRot = unit.zRot = 0;
        unit.xScale = unit.yScale = unit.zScale = 1;
        float partial = Mth.clamp(age - entity.tickCount, 0, 1);
        Motion motion = motions.computeIfAbsent(entity, e -> new Motion());
        // Peeking lifts the weapon from the reviewed kneel. Do not straighten
        // the legs (or float the arms) while the gameplay rise hint advances.
        float target = entity.isHalfCoverRising() || entity.isHalfCoverPeekIdle() || entity.isCrouching() ? 1 : 0;
        motion.update(age, target, entity.isAnimationWeaponReady() ? 1 : 0, Math.min(1, speed * 4));
        float coverTarget = entity.isHalfCoverPeekIdle() ? 1
            : entity.isHalfCoverRising() ? entity.getHalfCoverRiseProgress() : 0;
        motion.coverAim = Mth.lerp(motion.blend, motion.coverAim, coverTarget);
        weaponReady = motion.ready;
        float crawl = entity.getSwimAmount(partial);
        boolean gun = GunIntegration.isGun(entity.getMainHandItem());
        float trackingYawLimit = entity.isPassenger() ? 75.0F : 30.0F;
        // The renderer hands us (headYaw - bodyYaw) unwrapped for non-living mounts such as
        // SBW hulls. Near +-180 that is off by 360, which the clamp below turns into a
        // one-frame neck snap to the limit. Wrap before applying the seat's exact pose.
        yaw = Mth.wrapDegrees(yaw);
        float viewYaw = yaw;
        float viewPitch = pitch;
        if (entity.deathTime > 0) {
            apply((entity.getId() & 1) == 0 ? SoldierAnimations.UNIT_DEATH : SoldierAnimations.UNIT_DEATH_BACK,
                (entity.deathTime + partial) / 20, 1);
        } else {
            apply(SoldierAnimations.UNIT_IDLE, age / 20, 1 - motion.walk);
            applyLocomotion(swing, speed, motion.walk * (1 - crawl));
            head.yRot += Mth.clamp(viewYaw, -trackingYawLimit, trackingYawLimit) * Mth.DEG_TO_RAD;
            head.xRot += Mth.clamp(viewPitch, -25, 45) * Mth.DEG_TO_RAD;
            if (gun) {
                String id = entity.getMainHandItem().getTag() == null ? "" : entity.getMainHandItem().getTag().getString("GunId");
                if (!id.equals(motion.gunId)) { motion.gunId = id; motion.gunType = weaponType(entity); }
                applyWeaponPose(id, motion.gunType, motion.ready);
            }
            else {
                rightArm.xRot = Mth.cos(swing * .6662F + Mth.PI) * speed;
                leftArm.xRot = Mth.cos(swing * .6662F) * speed;
                rightArm.yRot = leftArm.yRot = rightArm.zRot = leftArm.zRot = 0;
            }
            applyReviewedPosture(motion.crouch * (1 - crawl), motion.coverAim,
                motion.ready, swing, speed, gun, viewYaw, viewPitch, trackingYawLimit);
            applyLocomotion(swing, speed, motion.walk * (1 - crawl) * (1 - motion.crouch));
            applyCrawl(crawl, swing, speed, pitch, gun);
            reviewedWeaponWeight = 1 - crawl;
            if (entity.hurtTime > 0)
                apply((entity.getId() & 1) == 0 ? SoldierAnimations.UNIT_HURT : SoldierAnimations.UNIT_HURT_1,
                    (entity.hurtDuration - entity.hurtTime + partial) / 10, 1);
        }
        if (entity.isPassenger() && entity.deathTime == 0) {
            rightLeg.xRot = leftLeg.xRot = -1.4F;
            rightLeg.yRot = .3F; leftLeg.yRot = -.3F;
            rightShin.xRot = leftShin.xRot = 1.2F;
        }
        hat.copyFrom(head);
    }

    private void apply(AnimationDefinition definition, float seconds, float weight) {
        if (weight > .0001F) KeyframeAnimations.animate(animationModel, definition,
            (long) (Math.max(0, seconds) * 1000), weight, scratch);
    }

    private void applyLocomotion(float swing, float speed, float weight) {
        // Limb swing is accumulated travel, not seconds. Use the vanilla stride
        // phase and full-length legs, without the source clip's hip/root bounce.
        float stride = Mth.cos(swing * .6662F) * Math.min(1, speed) * 1.4F;
        rightLeg.xRot = Mth.lerp(weight, rightLeg.xRot, stride);
        leftLeg.xRot = Mth.lerp(weight, leftLeg.xRot, -stride);
        rightLeg.y = Mth.lerp(weight, rightLeg.y, 6);
        leftLeg.y = Mth.lerp(weight, leftLeg.y, 6);
        rightLeg.z = Mth.lerp(weight, rightLeg.z, 0);
        leftLeg.z = Mth.lerp(weight, leftLeg.z, 0);
    }

    private void applyWeaponPose(String id, String type, float ready) {
        boolean heavy = id.contains("minigun"), pistol = type.equals("pistol");
        if (!heavy) {
            rightArm.xRot = Mth.lerp(ready, -.95F, rightArm.xRot);
            rightArm.yRot *= ready; rightArm.zRot *= ready;
            leftArm.xRot = Mth.lerp(ready, -.25F, leftArm.xRot);
            leftArm.yRot = Mth.lerp(ready, .5F, leftArm.yRot);
            leftArm.zRot = Mth.lerp(ready, .5F, leftArm.zRot);
        }
        float yaw = heavy ? .6F : pistol ? .7F : .65F, pitch = heavy ? .15F : pistol ? .9F : .85F;
        rightArm.yRot += head.yRot * yaw; leftArm.yRot += head.yRot * yaw;
        rightArm.xRot += head.xRot * pitch; leftArm.xRot += head.xRot * pitch;
        if (heavy) {
            rightArm.xRot += 1.90F; leftArm.xRot += .33F;
            rightArm.zRot += .12F; rightArm.yRot -= .15F;
            leftArm.zRot -= .25F; leftArm.yRot += .45F;
        } else if (pistol) {
            leftArm.x = 4; leftArm.y = -3; leftArm.z = -2; rightArm.z = 1;
            rightArm.yRot -= .12F; leftArm.yRot += .12F;
            rightArm.zRot += .04F; leftArm.zRot -= .04F;
        }
    }

    private static String weaponType(SoldierEntity entity) {
        // TaCZ remains optional; don't link its classes in the renderer.
        try {
            Object result = Class.forName("com.tacz.guns.api.TimelessAPI")
                .getMethod("getCommonGunIndex", net.minecraft.resources.ResourceLocation.class)
                .invoke(null, new net.minecraft.resources.ResourceLocation(entity.getMainHandItem().getTag().getString("GunId")));
            if (result instanceof java.util.Optional<?> index && index.isPresent())
                return String.valueOf(index.get().getClass().getMethod("getType").invoke(index.get()));
        } catch (ReflectiveOperationException | RuntimeException ignored) { }
        return "rifle";
    }

    private void applyCrouch(float amount, float swing, float speed) {
        applyReviewedPosture(amount, 0, weaponReady, swing, speed, true, 0, 0, 30.0F);
    }

    private void applyReviewedPosture(float amount, float cover, float ready,
                                      float swing, float speed, boolean gun, float yaw, float pitch,
                                      float yawLimit) {
        amount = Mth.clamp(amount, 0, 1);
        cover = Mth.clamp(cover, 0, 1);
        var crouchPose = ReviewedSoldierPoses.blend(ReviewedSoldierPoses.get("crouch"), ReviewedSoldierPoses.get("cover"), cover);
        var standingPose = ReviewedSoldierPoses.blend(ReviewedSoldierPoses.get("standing_lowered"), ReviewedSoldierPoses.get("standing_ready"), ready);
        Map<String, ModelPart> parts = Map.of("head", head, "body", body, "right_arm", rightArm, "left_arm", leftArm,
            "right_leg", rightLeg, "left_leg", leftLeg, "right_leg_shin", rightShin, "left_leg_shin", leftShin);
        Quaternionf tracking = new Quaternionf().rotationY(Mth.clamp(yaw, -yawLimit, yawLimit) * Mth.DEG_TO_RAD * .65F)
            .rotateX(Mth.clamp(pitch, -25, 45) * Mth.DEG_TO_RAD * .85F);
        for (var entry : parts.entrySet()) {
            String name = entry.getKey(); ModelPart part = entry.getValue();
            if (!gun && name.endsWith("_arm")) continue;
            var standingTarget = standingPose.parts().get(name);
            var target = ReviewedSoldierPoses.blend(standingTarget, crouchPose.parts().get(name), amount, name.endsWith("_arm"));
            if (name.endsWith("_shin")) {
                var shinTarget = crouchPose.parts().get(name);
                var current = new ReviewedSoldierPoses.Part(new Vector3f(part.x, part.y, part.z), ReviewedSoldierPoses.rotation(part), shinTarget.centre());
                var blended = ReviewedSoldierPoses.blend(current, shinTarget, amount, false);
                part.setPos(blended.position().x, blended.position().y, blended.position().z);
                ReviewedSoldierPoses.writeRotation(part, blended.rotation());
            } else {
                if (name.equals("head") || name.endsWith("_arm")) {
                    Quaternionf look = name.equals("head")
                        ? new Quaternionf().rotationY(Mth.clamp(yaw, -yawLimit, yawLimit) * Mth.DEG_TO_RAD)
                            .rotateX(Mth.clamp(pitch, -25, 45) * Mth.DEG_TO_RAD) : tracking;
                    target = new ReviewedSoldierPoses.Part(target.position(), new Quaternionf(look).mul(target.rotation()), target.centre());
                }
                ReviewedSoldierPoses.writeLocal(unit, part, target);
            }
            part.xScale = Mth.lerp(amount, part.xScale, 1);
            part.yScale = Mth.lerp(amount, part.yScale, 1);
            part.zScale = Mth.lerp(amount, part.zScale, 1);
        }
        // Moving sneak retains a symmetric gait, blending into the reviewed
        // planted kneel when movement stops. Standing/running is unchanged.
        float moving = Mth.clamp(speed * 6, 0, 1) * amount;
        if (moving > 0) {
            // A walking crouch needs room for two supporting legs. Tuck the
            // hips into the torso rather than driving a planted-knee hip height
            // through the floor when switching to the symmetric walking gait.
            Quaternionf rootRotation = ReviewedSoldierPoses.rotation(unit);
            for (ModelPart leg : new ModelPart[]{rightLeg, leftLeg}) {
                Vector3f hip = new Vector3f(leg.x, leg.y, leg.z).rotate(rootRotation).add(unit.x, unit.y, unit.z);
                hip.y = Mth.lerp(moving, hip.y, 14.2F);
                hip.z = Mth.lerp(moving, hip.z, 0);
                hip.sub(unit.x, unit.y, unit.z).rotate(new Quaternionf(rootRotation).invert());
                leg.setPos(hip.x, hip.y, hip.z);
            }
            rightLeg.xRot = Mth.lerp(moving, rightLeg.xRot, -.8F + .25F * Mth.cos(swing));
            leftLeg.xRot = Mth.lerp(moving, leftLeg.xRot, -.8F - .25F * Mth.cos(swing));
            rightShin.xRot = Mth.lerp(moving, rightShin.xRot, 1.55F);
            leftShin.xRot = Mth.lerp(moving, leftShin.xRot, 1.55F);
        }
        if (gun) {
            Quaternionf start = ReviewedSoldierPoses.weaponMount(standingPose);
            Quaternionf end = ReviewedSoldierPoses.weaponMount(crouchPose);
            reviewedWeaponRotation = new Quaternionf(tracking).mul(start.slerp(end, amount));
            reviewedWeaponWeight = 1;
        }
    }

    private void applyCrawl(float amount, float swing, float speed, float pitch, boolean gun) {
        if (amount <= 0) return;
        unit.y = Mth.lerp(amount, unit.y, 21); unit.z = Mth.lerp(amount, unit.z, 0);
        unit.xRot = Mth.lerp(amount, unit.xRot, Mth.HALF_PI);
        body.xRot *= 1 - amount; body.yRot *= 1 - amount; body.zRot *= 1 - amount;
        head.xRot = Mth.lerp(amount, head.xRot, -Mth.HALF_PI + Mth.clamp(pitch, -25, 30) * Mth.DEG_TO_RAD);
        head.y = Mth.lerp(amount, head.y, -5);
        head.z = Mth.lerp(amount, head.z, -1.8F);
        float gait = Mth.sin(swing * .8F) * Math.min(1, speed * 5);
        rightLeg.xRot = Mth.lerp(amount, rightLeg.xRot, .1F + gait * .35F);
        leftLeg.xRot = Mth.lerp(amount, leftLeg.xRot, .1F - gait * .35F);
        rightLeg.yRot *= 1 - amount; leftLeg.yRot *= 1 - amount;
        rightLeg.z *= 1 - amount; leftLeg.z *= 1 - amount;
        rightLeg.zRot = Mth.lerp(amount, rightLeg.zRot, .18F);
        leftLeg.zRot = Mth.lerp(amount, leftLeg.zRot, -.18F);
        rightShin.xRot = Mth.lerp(amount, rightShin.xRot, Math.max(0, gait) * .7F);
        leftShin.xRot = Mth.lerp(amount, leftShin.xRot, Math.max(0, -gait) * .7F);
        rightArm.xRot = Mth.lerp(amount, rightArm.xRot, -3.05F + gait * .08F);
        leftArm.xRot = Mth.lerp(amount, leftArm.xRot, -3.0F - gait * (gun ? .08F : .35F));
        rightArm.yRot = Mth.lerp(amount, rightArm.yRot, .1F);
        leftArm.yRot = Mth.lerp(amount, leftArm.yRot, .25F);
        rightArm.zRot *= 1 - amount; leftArm.zRot *= 1 - amount;
        rightArm.z *= 1 - amount; leftArm.z *= 1 - amount;
    }

    @Override public void translateToHand(HumanoidArm arm, PoseStack stack) {
        unit.translateAndRotate(stack);
        (arm == HumanoidArm.RIGHT ? rightArm : leftArm).translateAndRotate(stack);
    }

    public void translateWeaponToHand(PoseStack stack) {
        PoseStack hand = new PoseStack();
        translateToHand(HumanoidArm.RIGHT, hand);
        hand.translate(-1.0 / 16, 9.0 / 16, 0);
        if (reviewedWeaponRotation == null) {
            translateToHand(HumanoidArm.RIGHT, stack);
            stack.translate(-1.0 / 16, 9.0 / 16, 0);
        } else {
            Vector3f p = hand.last().pose().transformPosition(new Vector3f());
            Quaternionf actual = ReviewedSoldierPoses.rotation(unit).mul(ReviewedSoldierPoses.rotation(rightArm));
            stack.translate(p.x, p.y, p.z);
            stack.mulPose(actual.slerp(reviewedWeaponRotation, reviewedWeaponWeight));
        }
    }
    public float weaponReady() { return weaponReady; }

    public void translateBodyRoot(PoseStack stack) { unit.translateAndRotate(stack); }

    @Override public void copyPropertiesTo(HumanoidModel<T> target) {
        super.copyPropertiesTo(target);
        if (target instanceof SoldierModel<?> soldier) {
            soldier.unit.copyFrom(unit);
            soldier.rightShin.copyFrom(rightShin); soldier.leftShin.copyFrom(leftShin);
        }
    }
    @Override public void renderToBuffer(PoseStack stack, VertexConsumer consumer, int light, int overlay,
                                         float red, float green, float blue, float alpha) {
        unit.render(stack, consumer, light, overlay, red, green, blue, alpha);
    }

    private static final class Motion {
        float age = Float.NaN, crouch, ready, walk, coverAim, blend;
        String gunId, gunType = "rifle";
        void update(float now, float targetCrouch, float targetReady, float targetWalk) {
            if (Float.isNaN(age)) { age = now; crouch = targetCrouch; ready = targetReady; walk = targetWalk; blend = 1; return; }
            float dt = Mth.clamp(now - age, 0, 5); age = now;
            blend = 1 - (float) Math.exp(-dt * .45);
            crouch = Mth.lerp(blend, crouch, targetCrouch);
            ready = Mth.lerp(blend, ready, targetReady);
            walk = Mth.lerp(blend, walk, targetWalk);
        }
    }
}
