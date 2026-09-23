package com.stevesarmy.client.screen;

import com.stevesarmy.client.ClientFofState;
import com.stevesarmy.client.screen.widget.FofDropdownWidget;
import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.network.NetworkHandler;
import com.stevesarmy.network.SetFofStancePacket;
import com.stevesarmy.squad.FofStance;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.LivingEntity;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * In-world quick mark: opened by the mark-target keybind for the looked-at
 * player or soldier. Sets the stance on the owner's squad key, same as the
 * FoF tab's player rows.
 */
public class FofQuickMarkScreen extends Screen {
    private static final int BUTTON_WIDTH = 100;
    private static final int BUTTON_HEIGHT = 20;

    private final String targetName;
    @Nullable
    private final String subtitle;
    @Nullable
    private final UUID stanceKey;

    public FofQuickMarkScreen(LivingEntity target) {
        super(Component.literal("Mark Friend / Foe"));
        if (target instanceof SoldierEntity soldier) {
            this.stanceKey = soldier.getOwnerUUID().orElse(null);
            this.targetName = target.getName().getString();
            this.subtitle = "Marks the owner's whole squad";
        } else {
            this.stanceKey = target.getUUID();
            this.targetName = target.getName().getString();
            this.subtitle = null;
        }
    }

    @Override
    protected void init() {
        if (stanceKey == null) {
            onClose();
            return;
        }
        int centerX = width / 2;
        int top = height / 2 - 34;
        addRenderableWidget(Button.builder(Component.literal("Friendly"),
                button -> pick(FofStance.FRIENDLY))
            .bounds(centerX - BUTTON_WIDTH - 4, top, BUTTON_WIDTH, BUTTON_HEIGHT).build());
        addRenderableWidget(Button.builder(Component.literal("Neutral"),
                button -> pick(FofStance.NEUTRAL))
            .bounds(centerX + 4, top, BUTTON_WIDTH, BUTTON_HEIGHT).build());
        addRenderableWidget(Button.builder(Component.literal("Hostile"),
                button -> pick(FofStance.HOSTILE))
            .bounds(centerX - BUTTON_WIDTH - 4, top + BUTTON_HEIGHT + 8, BUTTON_WIDTH, BUTTON_HEIGHT).build());
        addRenderableWidget(Button.builder(Component.literal("Clear override"),
                button -> pick(null))
            .bounds(centerX + 4, top + BUTTON_HEIGHT + 8, BUTTON_WIDTH, BUTTON_HEIGHT).build());
    }

    private void pick(@Nullable FofStance stance) {
        if (stanceKey == null) return;
        NetworkHandler.INSTANCE.sendToServer(SetFofStancePacket.playerStance(stanceKey, stance));
        onClose();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);

        int centerX = width / 2;
        int top = height / 2 - 62;
        graphics.drawCenteredString(font, Component.literal("Mark: " + targetName), centerX, top, 0xFFFFFFFF);
        if (stanceKey != null) {
            FofStance current = ClientFofState.INSTANCE.getPlayerStance(stanceKey);
            String stanceText = current != null ? "Current: " + current.getDisplayName() : "Current: default";
            graphics.drawCenteredString(font, Component.literal(stanceText), centerX, top + 10,
                FofDropdownWidget.getStanceColor(current));
        }
        if (subtitle != null) {
            graphics.drawCenteredString(font, Component.literal(subtitle), centerX, top + 20, 0xFF888888);
        }

        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
