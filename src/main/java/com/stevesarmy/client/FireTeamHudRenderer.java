package com.stevesarmy.client;

import com.stevesarmy.squad.FireTeam;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import java.util.List;

/** Pure presentation of owner-only squad snapshots. */
public final class FireTeamHudRenderer {
    public enum Anchor { TOP_RIGHT, BOTTOM_RIGHT }
    private FireTeamHudRenderer() {}
    private static String tr(String key, Object... args) { return Component.translatable("hud.steves_army." + key, args).getString(); }
    public static void render(GuiGraphics g) {
        Minecraft mc = Minecraft.getInstance();
        if (!StevesArmyClientConfig.FIRETEAM_HUD_ENABLED.get() || mc.player == null || mc.level == null
                || mc.options.hideGui || mc.screen != null || mc.options.renderDebug) return;
        FireTeam selected = FireTeamScopeState.INSTANCE.getCurrentScope();
        List<FireTeamHudCache.Row> rows = FireTeamHudCache.rows().stream()
            .filter(r -> StevesArmyClientConfig.FIRETEAM_HUD_SHOW_EMPTY.get() || r.living() + r.unknown() > 0)
            .filter(r -> !StevesArmyClientConfig.FIRETEAM_HUD_SELECTED_ONLY.get() || selected == FireTeam.ALL || selected == r.team()).toList();
        if (rows.isEmpty() && !StevesArmyClientConfig.FIRETEAM_HUD_SHOW_EMPTY.get()) return;
        int width = StevesArmyClientConfig.FIRETEAM_HUD_WIDTH.get();
        int rowHeight = StevesArmyClientConfig.FIRETEAM_HUD_DETAILS.get() ? 43 : 18;
        int height = 31 + rows.size() * rowHeight;
        int sw = mc.getWindow().getGuiScaledWidth(), sh = mc.getWindow().getGuiScaledHeight();
        float scale = Math.min(StevesArmyClientConfig.FIRETEAM_HUD_SCALE.get().floatValue(), Math.min((sw - 8f) / width, (sh - 70f) / height));
        if (scale <= 0) return;
        int pw = (int)Math.ceil(width * scale), ph = (int)Math.ceil(height * scale);
        int x = sw - pw - StevesArmyClientConfig.FIRETEAM_HUD_OFFSET_X.get();
        // Reserve upper-right potion icons and bottom hotbar/status space.
        int top = mc.player.getActiveEffects().isEmpty() ? 6 : 58;
        int y = StevesArmyClientConfig.FIRETEAM_HUD_ANCHOR.get() == Anchor.TOP_RIGHT
            ? top + StevesArmyClientConfig.FIRETEAM_HUD_OFFSET_Y.get()
            : sh - 55 - ph - StevesArmyClientConfig.FIRETEAM_HUD_OFFSET_Y.get();
        x = Math.max(4, Math.min(x, sw - pw - 4));
        y = Math.max(4, Math.min(y, sh - ph - 4));
        g.pose().pushPose();
        g.pose().translate(x, y, 0); g.pose().scale(scale, scale, 1);
        int alpha = (int)(StevesArmyClientConfig.FIRETEAM_HUD_OPACITY.get() * 255) << 24;
        g.fill(0, 0, width, height, alpha | 0x101923);
        g.fill(0, 0, 2, height, 0xFF58C5DE);
        text(g, tr("title"), 7, 5, width - 14, 0xFFE6EEF5);
        FireTeam[] strip = {FireTeam.ALL, FireTeam.ALPHA, FireTeam.BRAVO, FireTeam.CHARLIE, FireTeam.DELTA};
        int cell = (width - 14) / 5;
        for (int i = 0; i < strip.length; i++) {
            int sx = 7 + i * cell;
            if (strip[i] == selected) g.fill(sx - 2, 16, sx + cell - 3, 28, 0xAA286279);
            text(g, strip[i].getShortName(), sx, 18, cell - 2, strip[i] == selected ? 0xFF8FE6FF : 0xFF8597A7);
        }
        int yRow = 32;
        for (var row : rows) {
            text(g, row.team().getShortName() + "  " + tr("living", row.living())
                + (row.unknown() > 0 ? "  " + tr("unknown", row.unknown()) : ""), 7, yRow, width - 14, 0xFFDFEAF0);
            if (StevesArmyClientConfig.FIRETEAM_HUD_DETAILS.get()) {
                var a = row.activity();
                String activity = a == null ? tr("idle") : tr("activity." + a.type().name().toLowerCase(java.util.Locale.ROOT));
                if (a != null && StevesArmyClientConfig.FIRETEAM_HUD_PROGRESS.get()) {
                    if (a.type().name().equals("GO_TO")) activity += " · " + (row.progress() < 0 ? tr("progress_unknown") : "~" + row.progress() + "%");
                }
                if (a != null && StevesArmyClientConfig.FIRETEAM_HUD_ETA.get()
                    && (a.type().name().equals("GO_TO") || a.type().name().equals("ATTACK"))) activity += " · " + (row.eta() < 0 ? tr("eta_unknown") : tr("eta_estimate", row.eta()));
                text(g, activity, 7, yRow + 11, width - 14, 0xFF9DAFBC);
                float suppression = row.suppression();
                text(g, tr("suppression") + " " + (suppression < 0 ? "?" : Math.round(suppression * 100) + "%"), 7, yRow + 22, width - 14, 0xFF9DAFBC);
                g.fill(7, yRow + 33, width - 7, yRow + 35, 0xFF2D3E4D);
                if (suppression >= 0) g.fill(7, yRow + 33, 7 + (int)((width - 14) * Math.max(0, Math.min(1, suppression))), yRow + 35,
                    suppression > .65 ? 0xFFE47E6C : suppression > .3 ? 0xFFE1BB70 : 0xFF64B9AD);
            }
            yRow += rowHeight;
        }
        g.pose().popPose();
    }
    private static void text(GuiGraphics g, String value, int x, int y, int width, int color) {
        var font = Minecraft.getInstance().font;
        g.drawString(font, font.plainSubstrByWidth(value, Math.max(0, width)), x, y, color, false);
    }
}
