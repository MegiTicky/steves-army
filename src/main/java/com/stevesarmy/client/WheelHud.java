package com.stevesarmy.client;

import com.stevesarmy.squad.FireTeam;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

/** Shared drawing for the wheel HUD: the fire-team scope badge shown at the wheel center. */
final class WheelHud {
    private WheelHud() {}

    static void drawScopeBadge(GuiGraphics guiGraphics, int centerX, int centerY) {
        Minecraft mc = Minecraft.getInstance();
        FireTeam scope = FireTeamScopeState.INSTANCE.getCurrentScope();
        String scopeLabel = "[" + scope.getShortName() + "]";
        int scopeColor = switch (scope) {
            case ALL -> 0xFFFFFFFF;
            case ALPHA -> 0xFFFF5555;
            case BRAVO -> 0xFF5555FF;
            case CHARLIE -> 0xFF55FF55;
            case DELTA -> 0xFFFFFF55;
            case GARRISON -> 0xFF55FFFF;
        };
        guiGraphics.drawCenteredString(mc.font, scopeLabel, centerX, centerY - mc.font.lineHeight / 2, scopeColor);
    }
}
