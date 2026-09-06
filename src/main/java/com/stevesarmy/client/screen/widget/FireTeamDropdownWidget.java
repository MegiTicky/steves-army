package com.stevesarmy.client.screen.widget;

import com.stevesarmy.squad.FireTeam;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.function.Consumer;

public class FireTeamDropdownWidget implements SquadControlWidget {
    private FireTeam currentTeam;
    private final List<FireTeam> options;
    private final Consumer<FireTeam> onChange;
    private boolean dropdownOpen = false;
    private static final int HEIGHT = 12;
    private static final int WIDTH = 42;

    public FireTeamDropdownWidget(FireTeam currentTeam, List<FireTeam> options, Consumer<FireTeam> onChange) {
        this.currentTeam = currentTeam;
        this.options = options;
        this.onChange = onChange;
    }

    public void setTeam(FireTeam team) {
        this.currentTeam = team;
    }

    public FireTeam getTeam() {
        return currentTeam;
    }

    public boolean isDropdownOpen() {
        return dropdownOpen;
    }

    public void openDropdown() {
        dropdownOpen = true;
    }

    public void closeDropdown() {
        dropdownOpen = false;
    }

    @Override
    public void render(GuiGraphics graphics, Font font, int x, int y, int width, int height, int mouseX, int mouseY) {
        int widgetWidth = width > 0 ? width : WIDTH;
        int bgColor = 0x88000000;
        int borderColor = 0xFF555555;

        graphics.fill(x, y, x + widgetWidth, y + HEIGHT, bgColor);
        graphics.fill(x, y, x + widgetWidth, y + 1, borderColor);
        graphics.fill(x, y + HEIGHT - 1, x + widgetWidth, y + HEIGHT, borderColor);
        graphics.fill(x, y, x + 1, y + HEIGHT, borderColor);
        graphics.fill(x + widgetWidth - 1, y, x + widgetWidth, y + HEIGHT, borderColor);

        String label = currentTeam.getDisplayName();
        graphics.drawString(font, Component.literal(label),
            x + Math.max(2, (widgetWidth - font.width(label)) / 2), y + 2, getTeamColor(currentTeam), false);

        if (dropdownOpen) {
            int dy = y + HEIGHT;
            for (FireTeam team : options) {
                String itemLabel = team.getDisplayName();
                boolean hovered = mouseX >= 0 && mouseX <= widgetWidth && mouseY >= dy - y && mouseY <= dy - y + HEIGHT;
                int bg = hovered ? 0xFF444444 : 0xFF222222;
                graphics.fill(x, dy, x + widgetWidth, dy + HEIGHT, bg);
                int itemTextColor = team == currentTeam ? 0xFFFFFFFF : getTeamColor(team);
                graphics.drawString(font, Component.literal(itemLabel),
                    x + Math.max(2, (widgetWidth - font.width(itemLabel)) / 2), dy + 2, itemTextColor, false);
                dy += HEIGHT;
            }
        }
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button != 0) return false;
        if (mx >= 0 && mx <= WIDTH && my >= 0 && my <= HEIGHT) {
            dropdownOpen = !dropdownOpen;
            return true;
        }
        if (dropdownOpen) {
            int dy = HEIGHT;
            for (FireTeam team : options) {
                if (mx >= 0 && mx <= WIDTH && my >= dy && my <= dy + HEIGHT) {
                    currentTeam = team;
                    onChange.accept(team);
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
        return dropdownOpen ? HEIGHT * (1 + options.size()) : HEIGHT;
    }

    @Override
    public int getWidth() {
        return WIDTH;
    }

    public static int getTeamColor(FireTeam team) {
        return switch (team) {
            case ALPHA -> 0xFFFF5555;
            case BRAVO -> 0xFF5555FF;
            case CHARLIE -> 0xFF55FF55;
            case DELTA -> 0xFFFFFF55;
            case GARRISON -> 0xFF55FFFF;
            default -> 0xFFFFFFFF;
        };
    }
}
