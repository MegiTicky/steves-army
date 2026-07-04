package com.stevesarmy.client;

import com.stevesarmy.combat.cover.CoverBehaviorManager;
import com.stevesarmy.combat.cover.CoverDebugManager;
import com.stevesarmy.combat.cover.CoverFinder;
import com.stevesarmy.combat.cover.CoverType;
import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.entity.ai.PeekController;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;

import java.util.Optional;
import java.util.UUID;

public class CoverHudRenderer {

    private static final int PANEL_X = 4;
    private static final int PANEL_Y = 4;
    private static final int LINE_HEIGHT = 10;

    private static boolean visible = false;

    public static boolean isVisible() {
        return visible;
    }

    public static void setVisible(boolean v) {
        visible = v;
    }

    public static void render(GuiGraphics guiGraphics) {
        if (!visible) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.font == null) return;

        int y = PANEL_Y;
        int x = PANEL_X;

        boolean header = false;
        for (SoldierEntity soldier : mc.level.getEntitiesOfClass(SoldierEntity.class,
                mc.player.getBoundingBox().inflate(50))) {

            Optional<UUID> ownerUUID = soldier.getOwnerUUID();
            if (ownerUUID.isEmpty() || !ownerUUID.get().equals(mc.player.getUUID())) continue;

            if (!header) {
                guiGraphics.drawString(mc.font, "--- Cover Top-3 ---", x, y, 0xFFFFAA00, true);
                y += LINE_HEIGHT + 4;
                header = true;
            }

            String nameLine = String.format("[%s #%d]", soldier.getName().getString(), soldier.getId());
            guiGraphics.drawString(mc.font, nameLine, x, y, 0xFFFFFFFF, true);
            y += LINE_HEIGHT;

            int stateOrdinal = soldier.getSyncedCoverState();
            CoverBehaviorManager.CoverState state = CoverBehaviorManager.CoverState.values()[stateOrdinal];
            int peekOrdinal = soldier.getSyncedPeekState();
            PeekController.State peekState = PeekController.State.values()[peekOrdinal];
            float suppression = soldier.getSyncedSuppressionLevel();

            String stateLine = String.format("  %s | Peek:%s | Supp:%.0f%% | %s",
                state.name(), peekState.name(), suppression * 100, soldier.getSquadMode().name());
            guiGraphics.drawString(mc.font, stateLine, x, y, 0xFFCCCCCC, false);
            y += LINE_HEIGHT;

            BlockPos currentPos = soldier.getSyncedCoverCurrentPos();
            if (!currentPos.equals(BlockPos.ZERO)) {
                float quality = soldier.getSyncedCoverCurrentQuality();
                int typeOrdinal = soldier.getSyncedCoverCurrentType();
                CoverType type = CoverType.values()[typeOrdinal];

                CoverDebugManager.TopCoversDebugData top = CoverDebugManager.getSoldierTopCovers(soldier.getId());
                int peeks = top != null ? top.peekCount : 0;
                float pen = top != null ? top.penalty : 0;

                String curLine = String.format("  Cur: %.3f %s (peeks:%d pen:%.2f)", quality, type.name(), peeks, pen);
                int curColor = peeks >= 4 ? 0xFFFFAA00 : 0xFF66FF66;
                guiGraphics.drawString(mc.font, curLine, x, y, curColor, false);
                y += LINE_HEIGHT;
            }

            CoverDebugManager.TopCoversDebugData topData = CoverDebugManager.getSoldierTopCovers(soldier.getId());
            if (topData != null && topData.topCovers != null && topData.topCovers.length > 0) {
                int[] rankColors = {0xFFFFD700, 0xFFC0C0C0, 0xFFCD7F32};
                for (int i = 0; i < topData.topCovers.length; i++) {
                    CoverFinder.ScoredCover sc = topData.topCovers[i];
                    String label = String.format("  #%d %d,%d,%d: %.3f %s", i + 1,
                        sc.cover.getPosition().getX(), sc.cover.getPosition().getY(), sc.cover.getPosition().getZ(),
                        sc.score, sc.cover.getType().name());
                    int color = i < rankColors.length ? rankColors[i] : 0xFF888888;
                    guiGraphics.drawString(mc.font, label, x, y, color, false);
                    y += LINE_HEIGHT;
                }
            }

            y += 4;
        }
    }
}
