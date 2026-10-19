package com.stevesarmy.client.model;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.stevesarmy.client.animation.SoldierAnimations;
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
        unit.x = 0; unit.y = 6; unit.z = -1; unit.xRot = .0873F;
        unit.yRot = unit.zRot = 0;
        unit.xScale = unit.yScale = unit.zScale = 1;
        float partial = Mth.clamp(age - entity.tickCount, 0, 1);
        Motion motion = motions.computeIfAbsent(entity, e -> new Motion());
        float target = entity.isHalfCoverRising() ? 1 - entity.getHalfCoverRiseProgress()
            : entity.isCrouching() ? 1 : 0;
        motion.update(age, target, entity.isAnimationWeaponReady() ? 1 : 0, Math.min(1, speed * 4));
        float coverTarget = entity.isHalfCoverPeekIdle() ? 1
            : entity.isHalfCoverRising() ? entity.getHalfCoverRiseProgress() : 0;
        motion.coverAim = Mth.lerp(motion.blend, motion.coverAim, coverTarget);
        weaponReady = motion.ready;
        float crawl = entity.getSwimAmount(partial);
        boolean gun = GunIntegration.isGun(entity.getMainHandItem());
        if (entity.deathTime > 0) {
            apply((entity.getId() & 1) == 0 ? SoldierAnimations.UNIT_DEATH : SoldierAnimations.UNIT_DEATH_BACK,
                (entity.deathTime + partial) / 20, 1);
        } else {
            apply(SoldierAnimations.UNIT_IDLE, age / 20, 1 - motion.walk);
            applyLocomotion(swing, speed, motion.walk * (1 - crawl));
            head.yRot += Mth.clamp(yaw, -30, 30) * Mth.DEG_TO_RAD;
            head.xRot += Mth.clamp(pitch, -25, 45) * Mth.DEG_TO_RAD;
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
            applyCrouch(motion.crouch * (1 - crawl), swing, speed);
            applyCrawl(crawl, swing, speed, pitch, gun);
            if (gun) applyCoverAim(motion.coverAim * (1 - crawl));
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

    private void applyCoverAim(float amount) {
        if (amount <= 0) return;
        PoseStack palm = new PoseStack();
        translateWeaponToHand(palm);
        float gripHeight = 1.5F - palm.last().pose().transformPosition(new Vector3f()).y;
        // Full block top + two model pixels. Move both hands with the weapon,
        // rather than translating the gun away from its right-hand attachment.
        float lift = Math.max(0, 1.125F - gripHeight) * 16 * amount / Mth.cos(unit.xRot);
        rightArm.y -= lift;
        leftArm.y -= lift;
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
        if (amount <= 0) return;
        // Standing -> tactical kneel; moving sneak -> symmetric bent-knee gait.
        float moving = Mth.clamp(speed * 6, 0, 1);
        unit.y += 5 * amount; unit.xRot += .12F * amount;
        body.xRot += .18F * amount;
        // Tuck the neck/shoulders into the leaned torso; never scale the legs.
        body.y += 3.4F * amount; head.y += 3.4F * amount;
        head.z -= 1.8F * amount; head.xRot -= .12F * amount;
        rightArm.y += 3.4F * amount; leftArm.y += 3.4F * amount;
        rightArm.z -= .8F * amount; leftArm.z -= .8F * amount;
        rightLeg.xRot = Mth.lerp(amount, rightLeg.xRot, Mth.lerp(moving, .5F, -.8F + .25F * Mth.cos(swing)));
        leftLeg.xRot = Mth.lerp(amount, leftLeg.xRot, Mth.lerp(moving, -1.45F, -.8F - .25F * Mth.cos(swing)));
        rightLeg.z = Mth.lerp(amount, rightLeg.z, 1.0F);
        leftLeg.z = Mth.lerp(amount, leftLeg.z, -1.5F);
        rightShin.xRot = amount * Mth.lerp(moving, -2.05F, 1.55F);
        leftShin.xRot = amount * Mth.lerp(moving, 1.6F, 1.55F);
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
        translateToHand(HumanoidArm.RIGHT, stack);
        stack.translate(-1.0 / 16, 9.0 / 16, 0);
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
