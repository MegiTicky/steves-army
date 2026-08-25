package com.stevesarmy.compat.ysm;

import com.elfmcys.yesstevemodel.client.gui.PlayerModelScreen;
import com.elfmcys.yesstevemodel.client.gui.PlayerTextureScreen;
import com.elfmcys.yesstevemodel.client.gui.button.TextureButton;
import com.elfmcys.yesstevemodel.client.entity.PlayerPreviewEntity;
import com.elfmcys.yesstevemodel.client.model.ModelAssembly;
import com.elfmcys.yesstevemodel.client.renderer.ModelPreviewRenderer;
import com.elfmcys.yesstevemodel.client.renderer.RendererManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.stevesarmy.entity.SoldierEntity;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/** Texture picker for a soldier's model. */
@OnlyIn(Dist.CLIENT)
public class SoldierTextureScreen extends PlayerTextureScreen {

    private final SoldierEntity soldier;

    public SoldierTextureScreen(PlayerModelScreen modelScreen, String modelId, ModelAssembly modelAssembly, SoldierEntity soldier) {
        super(modelScreen, modelId, modelAssembly);
        this.soldier = soldier;
    }

    @Override
    public TextureButton createTextureButton(int x, int y, PlayerPreviewEntity previewEntity, int textureIndex) {
        return new SoldierTextureButton(x, y, previewEntity, this.soldier, textureIndex, this.renderContext);
    }

    @Override
    public void renderTexturePreview(GuiGraphics guiGraphics, int scissorX, int scissorY, int scissorWidth, int scissorHeight, float partialTick) {
        RenderSystem.enableScissor(scissorX, scissorY, scissorWidth, scissorHeight);
        SoldierModelCapability capability = SoldierModelCapabilityProvider.get(this.soldier);
        if (capability != null) {
            this.modelHolder.initModelWithTexture(capability.getModelId(), capability.getCurrentTextureName());
        }
        ModelPreviewRenderer.renderEntityPreview(this.guiLeft + 149.5f + 40.0f + this.offsetX, this.guiTop + 117.5f + 80.0f + this.offsetY, this.zoom, this.pitch, this.yaw, partialTick, this.modelHolder, RendererManager.getPlayerRenderer(), this.showGround);
        RenderSystem.disableScissor();
    }
}
