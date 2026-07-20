package com.stevesarmy.client.screen.widget;

import com.stevesarmy.squad.SquadMode;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.util.function.Consumer;

public class SquadModeWidget implements SquadControlWidget {
    private SquadMode currentMode;
    private final Consumer<SquadMode> onChange;
    private boolean dropdownOpen = false;
    private static final int WIDTH = 50;
    private static final int HEIGHT = 12;

    public SquadModeWidget(SquadMode initialMode, Consumer<SquadMode> onChange) {
        this.currentMode = initialMode;
        this.onChange = onChange;
    }

    public void setMode(SquadMode mode) {
        this.currentMode = mode;
    }

    @Override
    public void render(GuiGraphics graphics, Font font, int x, int y, int width, int height, int mouseX, int mouseY) {
        int bgColor = 0x88000000;
        int borderColor = 0xFF555555;
        int textColor = 0xFFAAAAAA;

        graphics.fill(x, y, x + WIDTH, y + HEIGHT, bgColor);
        graphics.fill(x, y, x + WIDTH, y + 1, borderColor);
        graphics.fill(x, y + HEIGHT - 1, x + WIDTH, y + HEIGHT, borderColor);
        graphics.fill(x, y, x + 1, y + HEIGHT, borderColor);
        graphics.fill(x + WIDTH - 1, y, x + WIDTH, y + HEIGHT, borderColor);

        String label = switch (currentMode) {
            case FOLLOW -> "Fol";
            case HOLD -> "Hold";
        };
        graphics.drawString(font, Component.literal("[" + label + "▼]"), x + 3, y + 2, textColor, false);

        if (dropdownOpen) {
            int dy = y + HEIGHT;
            for (SquadMode mode : SquadMode.values()) {
                String itemLabel = switch (mode) { case FOLLOW -> "Fol"; case HOLD -> "Hold"; };
                boolean hovered = mouseX >= 0 && mouseX <= WIDTH && mouseY >= dy - y && mouseY <= dy - y + HEIGHT;
                graphics.fill(x, dy, x + WIDTH, dy + HEIGHT, hovered ? 0xFF444444 : 0xFF222222);
                graphics.drawString(font, Component.literal(itemLabel), x + 3, dy + 2, textColor, false);
                dy += HEIGHT;
            }
        }
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (mx >= 0 && mx <= WIDTH && my >= 0 && my <= HEIGHT) {
            dropdownOpen = !dropdownOpen;
            return true;
        }
        if (dropdownOpen) {
            int dy = HEIGHT;
            for (SquadMode mode : SquadMode.values()) {
                if (mx >= 0 && mx <= WIDTH && my >= dy && my <= dy + HEIGHT) {
                    currentMode = mode;
                    onChange.accept(mode);
                    dropdownOpen = false;
                    return true;
                }
                dy += HEIGHT;
            }
            dropdownOpen = false;
        }
        return false;
    }

    @Override
    public int getHeight() {
        return dropdownOpen ? HEIGHT * (1 + SquadMode.values().length) : HEIGHT;
    }

    @Override
    public int getWidth() {
        return WIDTH;
    }
}