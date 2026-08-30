package com.stevesarmy.client.screen.widget;

import com.stevesarmy.entity.SoldierRole;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.util.function.Consumer;

public class RoleDropdownWidget implements SquadControlWidget {
    private SoldierRole currentRole;
    private final Consumer<SoldierRole> onChange;
    private boolean dropdownOpen = false;
    private static final int HEIGHT = 12;
    private static final int WIDTH = 70;

    public RoleDropdownWidget(SoldierRole initialRole, Consumer<SoldierRole> onChange) {
        this.currentRole = initialRole;
        this.onChange = onChange;
    }

    public void setRole(SoldierRole role) {
        this.currentRole = role;
    }

    public SoldierRole getRole() {
        return currentRole;
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
        int textColor = 0xFFAAAAAA;

        graphics.fill(x, y, x + widgetWidth, y + HEIGHT, bgColor);
        graphics.fill(x, y, x + widgetWidth, y + 1, borderColor);
        graphics.fill(x, y + HEIGHT - 1, x + widgetWidth, y + HEIGHT, borderColor);
        graphics.fill(x, y, x + 1, y + HEIGHT, borderColor);
        graphics.fill(x + widgetWidth - 1, y, x + widgetWidth, y + HEIGHT, borderColor);

        String label = getRoleLabel(currentRole);
        String buttonLabel = "[ " + label + " ]";
        graphics.drawString(font, Component.literal(buttonLabel),
            x + Math.max(2, (widgetWidth - font.width(buttonLabel)) / 2), y + 2, textColor, false);

        if (dropdownOpen) {
            SoldierRole[] roles = SoldierRole.values();
            int dy = y + HEIGHT;
            for (SoldierRole role : roles) {
                String itemLabel = getRoleLabel(role);
                boolean hovered = mouseX >= 0 && mouseX <= widgetWidth && mouseY >= dy - y && mouseY <= dy - y + HEIGHT;
                int bg = hovered ? 0xFF444444 : 0xFF222222;
                graphics.fill(x, dy, x + widgetWidth, dy + HEIGHT, bg);
                int itemTextColor = role == currentRole ? 0xFFFFFFFF : 0xFFAAAAAA;
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
            for (SoldierRole role : SoldierRole.values()) {
                if (mx >= 0 && mx <= WIDTH && my >= dy && my <= dy + HEIGHT) {
                    currentRole = role;
                    onChange.accept(role);
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
        return dropdownOpen ? HEIGHT * (1 + SoldierRole.values().length) : HEIGHT;
    }

    @Override
    public int getWidth() {
        return WIDTH;
    }

    public static String getRoleLabel(SoldierRole role) {
        if (role == null) return "?";
        return role == SoldierRole.MACHINE_GUNNER ? "MG" : role.getDisplayName().getString();
    }
}
