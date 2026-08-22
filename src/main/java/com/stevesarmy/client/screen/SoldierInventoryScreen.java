package com.stevesarmy.client.screen;

import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.combat.GunIntegration;
import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.entity.SoldierRole;
import com.stevesarmy.inventory.SoldierInventory;
import com.stevesarmy.inventory.SoldierInventoryMenu;
import com.stevesarmy.network.NetworkHandler;
import com.stevesarmy.network.SetSoldierRolePacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;

public class SoldierInventoryScreen extends AbstractContainerScreen<SoldierInventoryMenu> {
    private static final int GUI_WIDTH = 176;
    private static final int GUI_HEIGHT = 189;
    private static final ResourceLocation CONTAINER_LOCATION = 
        new ResourceLocation(StevesArmyMod.MODID, "textures/gui/soldier_inventory.png");

    private Button rifleButton;
    private Button machineGunnerButton;

    public SoldierInventoryScreen(SoldierInventoryMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        this.imageWidth = GUI_WIDTH;
        this.imageHeight = GUI_HEIGHT;
        this.titleLabelX = 8;
        this.titleLabelY = 6;
    }

    @Override
    protected void init() {
        super.init();
        this.rifleButton = this.addRenderableWidget(Button.builder(
            Component.literal("Rifle"), button -> switchRole(SoldierRole.RIFLEMAN))
            .bounds(this.leftPos + 48, this.topPos + 4, 46, 14)
            .build());
        this.machineGunnerButton = this.addRenderableWidget(Button.builder(
            Component.literal("MG"), button -> switchRole(SoldierRole.MACHINE_GUNNER))
            .bounds(this.leftPos + 98, this.topPos + 4, 28, 14)
            .build());
        refreshRoleButtons();
    }

    @Override
    public void containerTick() {
        super.containerTick();
        refreshRoleButtons();
    }

    private void refreshRoleButtons() {
        SoldierEntity soldier = resolveSoldier();
        SoldierRole role = soldier != null ? soldier.getRole() : null;
        if (this.rifleButton != null) {
            this.rifleButton.active = role != null && role != SoldierRole.RIFLEMAN;
            this.rifleButton.visible = role != null;
        }
        if (this.machineGunnerButton != null) {
            this.machineGunnerButton.active = role != null && role != SoldierRole.MACHINE_GUNNER;
            this.machineGunnerButton.visible = role != null;
        }
    }

    @Nullable
    private SoldierEntity resolveSoldier() {
        if (this.minecraft != null && this.minecraft.player != null) {
            Entity entity = this.minecraft.player.level().getEntity(this.menu.getSoldierId());
            if (entity instanceof SoldierEntity soldier) {
                return soldier;
            }
        }
        return null;
    }

    private void switchRole(SoldierRole role) {
        if (this.minecraft != null && this.minecraft.getConnection() != null) {
            NetworkHandler.INSTANCE.sendToServer(new SetSoldierRolePacket(this.menu.getSoldierId(), role));
        }
        this.onClose();
    }

    @Override
    protected void renderBg(GuiGraphics guiGraphics, float partialTick, int mouseX, int mouseY) {
        int x = (this.width - this.imageWidth) / 2;
        int y = (this.height - this.imageHeight) / 2;
        guiGraphics.blit(CONTAINER_LOCATION, x, y, 0, 0, this.imageWidth, this.imageHeight, GUI_WIDTH, GUI_HEIGHT);
        // The texture predates the removal of the persistent offhand slot.
        guiGraphics.fill(x + 44, y + 90, x + 62, y + 108, 0xFFC6C6C6);
    }

    @Override
    protected void renderLabels(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        guiGraphics.drawString(this.font, this.title, this.titleLabelX, this.titleLabelY, 4210752, false);

        int totalAmmo = computeTotalAmmo();
        if (totalAmmo >= 0) {
            String ammoStr = String.valueOf(totalAmmo);
            guiGraphics.drawString(this.font, Component.literal("◆" + ammoStr),
                this.imageWidth - this.font.width(ammoStr) - 8, this.titleLabelY, 0xFFAAAAAA, false);
        }
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(guiGraphics);
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        this.renderTooltip(guiGraphics, mouseX, mouseY);
    }

    private int computeTotalAmmo() {
        ItemStack mainHand = this.menu.getSoldierInventoryItem(SoldierInventory.SLOT_MAIN_HAND);
        if (mainHand.isEmpty()) return -1;

        int magazineAmmo = GunIntegration.getCurrentAmmo(mainHand);
        int inventoryAmmo = 0;

        for (int i = SoldierInventory.SLOT_GENERAL_START; i < SoldierInventory.INVENTORY_SIZE; i++) {
            inventoryAmmo += GunIntegration.getAmmoCountForGun(mainHand, this.menu.getSoldierInventoryItem(i));
        }

        return magazineAmmo + inventoryAmmo;
    }
}
