package com.stevesarmy.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.stevesarmy.client.model.SoldierModel;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;

/** Preserves a mod's armor mesh while supplying our root and knee transforms. */
public final class ArticulatedArmorModel extends Model {
    private final Model equipment;
    private final SoldierModel<?> soldier;

    public ArticulatedArmorModel(Model equipment, SoldierModel<?> soldier) {
        super(equipment::renderType);
        this.equipment = equipment;
        this.soldier = soldier;
    }

    @Override public void renderToBuffer(PoseStack stack, VertexConsumer vertices, int light, int overlay,
                                         float r, float g, float b, float a) {
        stack.pushPose();
        soldier.translateBodyRoot(stack);
        try {
            if (!(equipment instanceof HumanoidModel<?> humanoid)) {
                equipment.renderToBuffer(stack, vertices, light, overlay, r, g, b, a);
                return;
            }
            // Render through the equipment's own method for helmets, vests and
            // shoulders; its rigid legs are rendered separately around our knees.
            boolean rightVisible = humanoid.rightLeg.visible, leftVisible = humanoid.leftLeg.visible;
            try {
                humanoid.rightLeg.visible = humanoid.leftLeg.visible = false;
                equipment.renderToBuffer(stack, vertices, light, overlay, r, g, b, a);
            } finally {
                humanoid.rightLeg.visible = rightVisible; humanoid.leftLeg.visible = leftVisible;
            }
            if (rightVisible) renderLeg(stack, vertices, humanoid.rightLeg, soldier.rightLeg, light, overlay, r, g, b, a);
            if (leftVisible) renderLeg(stack, vertices, humanoid.leftLeg, soldier.leftLeg, light, overlay, r, g, b, a);
        } finally { stack.popPose(); }
    }

    private static void renderLeg(PoseStack world, VertexConsumer vertices, ModelPart equipmentLeg,
                                   ModelPart thigh, int light, int overlay, float r, float g, float b, float a) {
        world.pushPose();
        thigh.translateAndRotate(world);
        PoseStack.Pose upper = world.last();
        world.pushPose();
        thigh.getChild("lower_leg").translateAndRotate(world);
        world.translate(0, -6.0 / 16, 0);
        PoseStack.Pose lower = world.last();
        // Render the borrowed mesh in leg-local coordinates, not its copied hip
        // transform. Child details (kneepads, pouches, boots) retain their poses.
        PartPose oldPose = equipmentLeg.storePose();
        float sx = equipmentLeg.xScale, sy = equipmentLeg.yScale, sz = equipmentLeg.zScale;
        try {
            equipmentLeg.loadPose(PartPose.ZERO);
            equipmentLeg.xScale = equipmentLeg.yScale = equipmentLeg.zScale = 1;
            equipmentLeg.render(new PoseStack(), new KneeArmorVertexConsumer(vertices, upper, lower),
                light, overlay, r, g, b, a);
        } finally {
            equipmentLeg.loadPose(oldPose);
            equipmentLeg.xScale = sx; equipmentLeg.yScale = sy; equipmentLeg.zScale = sz;
            world.popPose(); world.popPose();
        }
    }
}
