package com.stevesarmy.client;

import com.stevesarmy.network.FireTeamSuppressionSyncPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

public final class FireTeamSuppressionOverlay {
    private FireTeamSuppressionOverlay() {}
    public static void render(GuiGraphics g) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        var entries = ClientFireTeamSuppressionData.INSTANCE.getEntries();
        if (entries.isEmpty()) return;
        int y = 6;
        for (FireTeamSuppressionSyncPacket.Entry e : entries) {
            int color = e.state() == 2 ? 0xFFFF5555 : e.state() == 1 ? 0xFFFFAA00 : 0xFF55FF55;
            String label = e.fireTeam().name() + " " + String.format("%.2f", e.level()) + (e.state()==2?" HEAVY":e.state()==1?" SUPP":"");
            g.drawString(mc.font, label, 6, y, color, true);
            y += 10;
        }
    }
}
