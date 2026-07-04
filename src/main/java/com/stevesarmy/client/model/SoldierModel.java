package com.stevesarmy.client.model;

import com.stevesarmy.entity.SoldierEntity;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.util.Mth;

public class SoldierModel extends HumanoidModel<SoldierEntity> {

    private static final float EXIT_THRESHOLD = 0.15F;

    public SoldierModel(ModelPart root) {
        super(root);
    }

    public static LayerDefinition createBodyLayer() {
        MeshDefinition mesh = HumanoidModel.createMesh(CubeDeformation.NONE, 0.0F);
        return LayerDefinition.create(mesh, 64, 64);
    }

    @Override
    public void setupAnim(SoldierEntity entity, float limbSwing, float limbSwingAmount,
                          float ageInTicks, float netHeadYaw, float headPitch) {
        super.setupAnim(entity, limbSwing, limbSwingAmount, ageInTicks, netHeadYaw, headPitch);

        float f = this.swimAmount;

        // Entry: isCrawling() true, apply prone pose immediately (lerp from standing to prone).
        // Exit: isCrawling() false, keep prone pose while body is still significantly rotated,
        //       then switch to standing pose once body is nearly upright (f < EXIT_THRESHOLD).
        // At EXIT_THRESHOLD: body rotation = -90 * 0.15 = -13.5°, arm snap is tiny.
        boolean applyProne = entity.isCrawling() || f > EXIT_THRESHOLD;

        if (!applyProne) {
            return;
        }

        // RIGHT ARM
        if (entity.isCrawling()) {
            this.rightArm.xRot = Mth.lerp(f, this.rightArm.xRot, PoseConfig.RA_X);
        } else {
            this.rightArm.xRot = Mth.lerp(1.0F, this.rightArm.xRot, PoseConfig.RA_X);
        }
        this.rightArm.yRot = Mth.lerp(f, this.rightArm.yRot, PoseConfig.RA_Y);
        this.rightArm.zRot = Mth.lerp(f, this.rightArm.zRot, PoseConfig.RA_Z);
        this.rightArm.x = Mth.lerp(f, this.rightArm.x, -5.0F + PoseConfig.RA_POS_X);
        this.rightArm.y = Mth.lerp(f, this.rightArm.y, 2.0F + PoseConfig.RA_POS_Y);
        this.rightArm.z = Mth.lerp(f, this.rightArm.z, 0.0F + PoseConfig.RA_POS_Z);

        // LEFT ARM
        if (entity.isCrawling()) {
            this.leftArm.xRot = Mth.lerp(f, this.leftArm.xRot, PoseConfig.LA_X);
        } else {
            this.leftArm.xRot = Mth.lerp(1.0F, this.leftArm.xRot, PoseConfig.LA_X);
        }
        this.leftArm.yRot = Mth.lerp(f, this.leftArm.yRot, PoseConfig.LA_Y);
        this.leftArm.zRot = Mth.lerp(f, this.leftArm.zRot, PoseConfig.LA_Z);
        this.leftArm.x = Mth.lerp(f, this.leftArm.x, 5.0F + PoseConfig.LA_POS_X);
        this.leftArm.y = Mth.lerp(f, this.leftArm.y, 2.0F + PoseConfig.LA_POS_Y);
        this.leftArm.z = Mth.lerp(f, this.leftArm.z, 0.0F + PoseConfig.LA_POS_Z);

        // HEAD
        this.head.xRot = Mth.clamp(
            Mth.lerp(f, this.head.xRot, headPitch * Mth.DEG_TO_RAD + PoseConfig.H_X),
            PoseConfig.H_CLAMP_MIN, PoseConfig.H_CLAMP_MAX);
        this.head.yRot = netHeadYaw * Mth.DEG_TO_RAD;

        // BODY
        this.body.xRot = Mth.lerp(f, this.body.xRot, PoseConfig.B_X);
        this.body.yRot = Mth.lerp(f, this.body.yRot, PoseConfig.B_Y);
        this.body.zRot = Mth.lerp(f, this.body.zRot, PoseConfig.B_Z);

        // RIGHT LEG
        this.rightLeg.xRot = Mth.lerp(f, this.rightLeg.xRot, PoseConfig.RL_X);
        this.rightLeg.yRot = Mth.lerp(f, this.rightLeg.yRot, PoseConfig.RL_Y);
        this.rightLeg.zRot = Mth.lerp(f, this.rightLeg.zRot, PoseConfig.RL_Z);
        this.rightLeg.z = Mth.lerp(f, this.rightLeg.z, 0.0F + PoseConfig.RL_POS_Z);

        // LEFT LEG
        this.leftLeg.xRot = Mth.lerp(f, this.leftLeg.xRot, PoseConfig.LL_X);
        this.leftLeg.yRot = Mth.lerp(f, this.leftLeg.yRot, PoseConfig.LL_Y);
        this.leftLeg.zRot = Mth.lerp(f, this.leftLeg.zRot, PoseConfig.LL_Z);
        this.leftLeg.z = Mth.lerp(f, this.leftLeg.z, 0.0F + PoseConfig.LL_POS_Z);

        this.hat.copyFrom(this.head);
    }
}