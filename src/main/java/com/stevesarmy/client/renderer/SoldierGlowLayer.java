package com.stevesarmy.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.entity.EnemySoldierEntity;
import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.squad.TeamManager;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;

/**
 * No longer used. Vanilla glow outline via MobEffects.GLOWING + team color
 * handles the colored glow effect visible through walls.
 */
@Deprecated
public class SoldierGlowLayer<T extends LivingEntity, M extends HumanoidModel<T>> extends RenderLayer<T, M> {

    private static final ResourceLocation PLAYER_TEXTURE = new ResourceLocation("minecraft", "textures/entity/player/wide/steve.png");

    public SoldierGlowLayer(LivingEntityRenderer<T, M> renderer) {
        super(renderer);
    }

    @Override
    public void render(PoseStack poseStack, MultiBufferSource bufferSource, int packedLight,
                       T entity, float limbSwing, float limbSwingAmount,
                       float partialTick, float ageInTicks, float headYaw, float headPitch) {
        // Method kept for compilation but no longer added to any renderer.
        // Use vanilla MobEffects.GLOWING instead.
    }
}