package com.stevesarmy.client.screen.widget;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.util.function.Consumer;

public class OpenInventoryButton implements SquadControlWidget {
    private final Runnable onClick;
    private boolean inRange;
    private static final int WIDTH = 56;
    private static final int HEIGHT = 12;

    public OpenInventoryButton(Runnable onClick) {
        this.onClick = onClick;
        this.inRange = true;
    }

    public void setInRange(boolean inRange) {
        this.inRange = inRange;
    }

    @Override
    public void render(GuiGraphics graphics, Font font, int x, int y, int width, int height, int mouseX, int mouseY) {
        boolean hovered = inRange && mouseX >= 0 && mouseX <= WIDTH && mouseY >= 0 && mouseY <= HEIGHT;
        int bgColor = hovered ? 0xFF444444 : (inRange ? 0x88000000 : 0x44000000);
        int borderColor = inRange ? 0xFF555555 : 0xFF333333;
        int textColor = inRange ? 0xFFAAAAAA : 0xFF555555;

        graphics.fill(x, y, x + WIDTH, y + HEIGHT, bgColor);
        graphics.fill(x, y, x + WIDTH, y + 1, borderColor);
        graphics.fill(x, y + HEIGHT - 1, x + WIDTH, y + HEIGHT, borderColor);
        graphics.fill(x, y, x + 1, y + HEIGHT, borderColor);
        graphics.fill(x + WIDTH - 1, y, x + WIDTH, y + HEIGHT, borderColor);

        graphics.drawString(font, Component.literal("Inventory"), x + 2, y + 2, textColor, false);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (mx >= 0 && mx <= WIDTH && my >= 0 && my <= HEIGHT) {
            if (inRange) {
                onClick.run();
            } else {
                Minecraft mc = Minecraft.getInstance();
                if (mc.player != null) {
                    mc.player.displayClientMessage(Component.literal("Soldier too far away to open inventory"), true);
                }
            }
            return true;
        }
        return false;
    }

    @Override
    public int getHeight() {
        return HEIGHT;
    }

    @Override
    public int getWidth() {
        return WIDTH;
    }
}