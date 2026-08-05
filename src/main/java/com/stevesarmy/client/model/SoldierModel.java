package com.stevesarmy.client.model;

import com.stevesarmy.entity.SoldierEntity;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.util.Mth;

public class SoldierModel<T extends SoldierEntity> extends HumanoidModel<T> {

    private static final float EXIT_THRESHOLD = 0.15F;
    private static final float CRAWL_MOVEMENT_THRESHOLD = 0.05F;

    public SoldierModel(ModelPart root) {
        super(root);
    }

    public static LayerDefinition createBodyLayer() {
        MeshDefinition mesh = HumanoidModel.createMesh(CubeDeformation.NONE, 0.0F);
        return LayerDefinition.create(mesh, 64, 64);
    }

    @Override
    public void setupAnim(T entity, float limbSwing, float limbSwingAmount,
                          float ageInTicks, float netHeadYaw, float headPitch) {
        super.setupAnim(entity, limbSwing, limbSwingAmount, ageInTicks, netHeadYaw, headPitch);

        if (entity.isHalfCoverRising()) {
            // HumanoidModel only has a binary crouching pose. Re-run its pose
            // setup once standing and blend the two transforms for the rise.
            float[] crouched = capturePose();
            this.crouching = false;
            super.setupAnim(entity, limbSwing, limbSwingAmount, ageInTicks, netHeadYaw, headPitch);
            float[] standing = capturePose();
            this.crouching = true;
            blendPose(crouched, standing, entity.getHalfCoverRiseProgress());
        }

        float f = this.swimAmount;

        boolean applyProne = entity.isLowCrouching() || f > EXIT_THRESHOLD;

        if (!applyProne) {
            return;
        }

        // Keep the custom prone gun-holding pose while stationary, but let the
        // vanilla swim animation provide limb motion during land crawling.
        boolean crawlingAndMoving = entity.isLowCrouching()
            && (limbSwingAmount > CRAWL_MOVEMENT_THRESHOLD
                || entity.getDeltaMovement().horizontalDistanceSqr() > 0.0004D);
        if (crawlingAndMoving) {
            return;
        }

        if (entity.isLowCrouching()) {
            this.rightArm.xRot = Mth.lerp(f, this.rightArm.xRot, PoseConfig.RA_X);
        } else {
            this.rightArm.xRot = Mth.lerp(1.0F, this.rightArm.xRot, PoseConfig.RA_X);
        }
        this.rightArm.yRot = Mth.lerp(f, this.rightArm.yRot, PoseConfig.RA_Y);
        this.rightArm.zRot = Mth.lerp(f, this.rightArm.zRot, PoseConfig.RA_Z);
        this.rightArm.x = Mth.lerp(f, this.rightArm.x, -5.0F + PoseConfig.RA_POS_X);
        this.rightArm.y = Mth.lerp(f, this.rightArm.y, 2.0F + PoseConfig.RA_POS_Y);
        this.rightArm.z = Mth.lerp(f, this.rightArm.z, 0.0F + PoseConfig.RA_POS_Z);

        if (entity.isLowCrouching()) {
            this.leftArm.xRot = Mth.lerp(f, this.leftArm.xRot, PoseConfig.LA_X);
        } else {
            this.leftArm.xRot = Mth.lerp(1.0F, this.leftArm.xRot, PoseConfig.LA_X);
        }
        this.leftArm.yRot = Mth.lerp(f, this.leftArm.yRot, PoseConfig.LA_Y);
        this.leftArm.zRot = Mth.lerp(f, this.leftArm.zRot, PoseConfig.LA_Z);
        this.leftArm.x = Mth.lerp(f, this.leftArm.x, 5.0F + PoseConfig.LA_POS_X);
        this.leftArm.y = Mth.lerp(f, this.leftArm.y, 2.0F + PoseConfig.LA_POS_Y);
        this.leftArm.z = Mth.lerp(f, this.leftArm.z, 0.0F + PoseConfig.LA_POS_Z);

        this.head.xRot = Mth.clamp(
            Mth.lerp(f, this.head.xRot, headPitch * Mth.DEG_TO_RAD + PoseConfig.H_X),
            PoseConfig.H_CLAMP_MIN, PoseConfig.H_CLAMP_MAX);
        this.head.yRot = netHeadYaw * Mth.DEG_TO_RAD;

        this.body.xRot = Mth.lerp(f, this.body.xRot, PoseConfig.B_X);
        this.body.yRot = Mth.lerp(f, this.body.yRot, PoseConfig.B_Y);
        this.body.zRot = Mth.lerp(f, this.body.zRot, PoseConfig.B_Z);

        this.rightLeg.xRot = Mth.lerp(f, this.rightLeg.xRot, PoseConfig.RL_X);
        this.rightLeg.yRot = Mth.lerp(f, this.rightLeg.yRot, PoseConfig.RL_Y);
        this.rightLeg.zRot = Mth.lerp(f, this.rightLeg.zRot, PoseConfig.RL_Z);
        this.rightLeg.z = Mth.lerp(f, this.rightLeg.z, 0.0F + PoseConfig.RL_POS_Z);

        this.leftLeg.xRot = Mth.lerp(f, this.leftLeg.xRot, PoseConfig.LL_X);
        this.leftLeg.yRot = Mth.lerp(f, this.leftLeg.yRot, PoseConfig.LL_Y);
        this.leftLeg.zRot = Mth.lerp(f, this.leftLeg.zRot, PoseConfig.LL_Z);
        this.leftLeg.z = Mth.lerp(f, this.leftLeg.z, 0.0F + PoseConfig.LL_POS_Z);

        this.hat.copyFrom(this.head);
    }

    private float[] capturePose() {
        return new float[]{
            head.xRot, head.yRot, head.zRot, head.x, head.y, head.z,
            body.xRot, body.yRot, body.zRot, body.x, body.y, body.z,
            rightArm.xRot, rightArm.yRot, rightArm.zRot, rightArm.x, rightArm.y, rightArm.z,
            leftArm.xRot, leftArm.yRot, leftArm.zRot, leftArm.x, leftArm.y, leftArm.z,
            rightLeg.xRot, rightLeg.yRot, rightLeg.zRot, rightLeg.x, rightLeg.y, rightLeg.z,
            leftLeg.xRot, leftLeg.yRot, leftLeg.zRot, leftLeg.x, leftLeg.y, leftLeg.z
        };
    }

    private void blendPose(float[] crouched, float[] standing, float progress) {
        float p = Mth.clamp(progress, 0.0F, 1.0F);
        int i = 0;
        i = blendPart(head, crouched, standing, i, p);
        i = blendPart(body, crouched, standing, i, p);
        i = blendPart(rightArm, crouched, standing, i, p);
        i = blendPart(leftArm, crouched, standing, i, p);
        i = blendPart(rightLeg, crouched, standing, i, p);
        blendPart(leftLeg, crouched, standing, i, p);
    }

    private static int blendPart(ModelPart part, float[] crouched, float[] standing,
                                 int index, float progress) {
        part.xRot = Mth.lerp(progress, crouched[index], standing[index]);
        part.yRot = Mth.lerp(progress, crouched[index + 1], standing[index + 1]);
        part.zRot = Mth.lerp(progress, crouched[index + 2], standing[index + 2]);
        part.x = Mth.lerp(progress, crouched[index + 3], standing[index + 3]);
        part.y = Mth.lerp(progress, crouched[index + 4], standing[index + 4]);
        part.z = Mth.lerp(progress, crouched[index + 5], standing[index + 5]);
        return index + 6;
    }
}
