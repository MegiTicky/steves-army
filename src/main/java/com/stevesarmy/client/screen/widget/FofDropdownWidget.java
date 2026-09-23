package com.stevesarmy.client.screen.widget;

import com.stevesarmy.squad.FofStance;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import javax.annotation.Nullable;
import java.util.List;
import java.util.function.Consumer;

/**
 * Stance dropdown for FoF rows. The current value is a {@link FofStance} or
 * null for "Default" (no override — the built-in rules apply).
 */
public class FofDropdownWidget implements SquadControlWidget {
    public record Option(@Nullable FofStance stance, String label) {
        public static final Option DEFAULT = new Option(null, "Default");

        public static Option of(FofStance stance) {
            return new Option(stance, stance.getDisplayName());
        }
    }

    private static final int HEIGHT = 12;
    private static final int WIDTH = 110;

    private final List<Option> options;
    private final Consumer<Option> onChange;
    private boolean dropdownOpen = false;
    @Nullable
    private FofStance currentStance;

    public FofDropdownWidget(@Nullable FofStance currentStance, List<Option> options, Consumer<Option> onChange) {
        this.currentStance = currentStance;
        this.options = options;
        this.onChange = onChange;
    }

    public static List<Option> standardOptions() {
        return List.of(Option.DEFAULT, Option.of(FofStance.FRIENDLY),
            Option.of(FofStance.NEUTRAL), Option.of(FofStance.HOSTILE));
    }

    public void setStance(@Nullable FofStance stance) {
        this.currentStance = stance;
    }

    public boolean isDropdownOpen() {
        return dropdownOpen;
    }

    public void openDropdown() {
        dropdownOpen = true;
    }

    @Override
    public void render(GuiGraphics graphics, Font font, int x, int y, int width, int height, int mouseX, int mouseY) {
        int bgColor = 0x88000000;
        int borderColor = 0xFF555555;

        graphics.fill(x, y, x + WIDTH, y + HEIGHT, bgColor);
        graphics.fill(x, y, x + WIDTH, y + 1, borderColor);
        graphics.fill(x, y + HEIGHT - 1, x + WIDTH, y + HEIGHT, borderColor);
        graphics.fill(x, y, x + 1, y + HEIGHT, borderColor);
        graphics.fill(x + WIDTH - 1, y, x + WIDTH, y + HEIGHT, borderColor);

        String label = currentStance != null ? currentStance.getDisplayName() : "Default";
        graphics.drawString(font, Component.literal(label),
            x + Math.max(2, (WIDTH - font.width(label)) / 2), y + 2, getStanceColor(currentStance), false);

        if (dropdownOpen) {
            int dy = y + HEIGHT;
            for (Option option : options) {
                String itemLabel = option.label();
                boolean hovered = mouseX >= 0 && mouseX <= WIDTH && mouseY >= dy - y && mouseY <= dy - y + HEIGHT;
                int bg = hovered ? 0xFF444444 : 0xFF222222;
                graphics.fill(x, dy, x + WIDTH, dy + HEIGHT, bg);
                boolean selected = option.stance() == currentStance
                    || (option.stance() == null && currentStance == null);
                int itemTextColor = selected ? 0xFFFFFFFF : getStanceColor(option.stance());
                graphics.drawString(font, Component.literal(itemLabel),
                    x + Math.max(2, (WIDTH - font.width(itemLabel)) / 2), dy + 2, itemTextColor, false);
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
            for (Option option : options) {
                if (mx >= 0 && mx <= WIDTH && my >= dy && my <= dy + HEIGHT) {
                    currentStance = option.stance();
                    onChange.accept(option);
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

    public static int getStanceColor(@Nullable FofStance stance) {
        if (stance == null) return 0xFFAAAAAA;
        return switch (stance) {
            case FRIENDLY -> 0xFF55FF55;
            case NEUTRAL -> 0xFFFFFF55;
            case HOSTILE -> 0xFFFF5555;
        };
    }
}
