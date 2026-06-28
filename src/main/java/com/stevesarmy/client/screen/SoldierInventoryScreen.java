package com.stevesarmy.client.screen;

import com.stevesarmy.inventory.SoldierInventoryMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;

public class SoldierInventoryScreen extends AbstractContainerScreen<SoldierInventoryMenu> {
    private static final ResourceLocation CONTAINER_LOCATION = 
        new ResourceLocation("minecraft", "textures/gui/container/generic_54.png");

    public SoldierInventoryScreen(SoldierInventoryMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        this.imageHeight = 150;
    }

    @Override
    protected void renderBg(GuiGraphics guiGraphics, float partialTick, int mouseX, int mouseY) {
        int x = (this.width - this.imageWidth) / 2;
        int y = (this.height - this.imageHeight) / 2;
        
        guiGraphics.blit(CONTAINER_LOCATION, x, y, 0, 0, this.imageWidth, 36);
        guiGraphics.blit(CONTAINER_LOCATION, x, y + 36, 0, 126, this.imageWidth, 114);
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        this.renderTooltip(guiGraphics, mouseX, mouseY);
    }
}