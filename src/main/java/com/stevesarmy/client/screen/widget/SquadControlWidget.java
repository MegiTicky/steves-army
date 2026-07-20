package com.stevesarmy.client.screen.widget;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

public interface SquadControlWidget {
    void render(GuiGraphics graphics, Font font, int x, int y, int width, int height, int mouseX, int mouseY);
    boolean mouseClicked(double mx, double my, int button);
    int getHeight();
    int getWidth();
}