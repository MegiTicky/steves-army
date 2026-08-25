package com.stevesarmy.compat.ysm;

import com.elfmcys.yesstevemodel.client.gui.ModelInfoScreen;
import com.elfmcys.yesstevemodel.client.gui.PlayerModelScreen;
import com.elfmcys.yesstevemodel.client.gui.PlayerTextureScreen;
import com.elfmcys.yesstevemodel.client.gui.button.ModelButton;
import com.elfmcys.yesstevemodel.client.entity.PlayerPreviewEntity;
import com.elfmcys.yesstevemodel.client.model.ModelAssembly;
import com.mojang.blaze3d.systems.RenderSystem;
import com.stevesarmy.entity.SoldierEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/** YSM model picker targeted at a specific soldier. */
@OnlyIn(Dist.CLIENT)
public class SoldierModelScreen extends PlayerModelScreen {

    private final SoldierEntity soldier;

    public SoldierModelScreen(SoldierEntity soldier) {
        this.soldier = soldier;
    }

    @Override
    public ModelButton createModelButton(int x, int y, boolean isAuthLocked, PlayerPreviewEntity previewEntity, ModelAssembly modelAssembly) {
        return new SoldierModelButton(x, y, isAuthLocked, previewEntity, modelAssembly, this.soldier);
    }

    @Override
    public PlayerTextureScreen createTextureScreen(PlayerModelScreen modelScreen, String str, ModelAssembly modelAssembly) {
        SoldierModelCapability capability = SoldierModelCapabilityProvider.get(this.soldier);
        ModelAssembly target = capability != null ? capability.getModelAssembly() : null;
        String modelId = capability != null && capability.getModelId() != null ? capability.getModelId() : str;
        return new SoldierTextureScreen(modelScreen, modelId, target != null ? target : modelAssembly, this.soldier);
    }

    @Override
    public ModelInfoScreen createModelInfoScreen(PlayerModelScreen modelScreen, ModelAssembly modelAssembly) {
        SoldierModelCapability capability = SoldierModelCapabilityProvider.get(this.soldier);
        ModelAssembly target = capability != null ? capability.getModelAssembly() : null;
        return new ModelInfoScreen(modelScreen, target != null ? target : modelAssembly);
    }

    @Override
    public void renderModelPreview(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        double guiScale = Minecraft.getInstance().getWindow().getGuiScale();
        RenderSystem.enableScissor((int) ((this.guiLeft + 5) * guiScale), (int) (Minecraft.getInstance().getWindow().getHeight() - ((this.guiTop + 200) * guiScale)), (int) (125.0d * guiScale), (int) (171.0d * guiScale));
        InventoryScreen.renderEntityInInventoryFollowsMouse(guiGraphics, this.guiLeft + 67, this.guiTop + 190, 70, (this.guiLeft + 67) - mouseX, ((this.guiTop + 180) - 95) - mouseY, this.soldier);
        RenderSystem.disableScissor();
    }
}
