package com.stevesarmy.client.screen;

import com.stevesarmy.StevesArmyConfig;
import com.stevesarmy.client.ClientSquadData;
import com.stevesarmy.client.FireTeamScopeState;
import com.stevesarmy.client.screen.widget.OpenInventoryButton;
import com.stevesarmy.network.NetworkHandler;
import com.stevesarmy.network.OpenSoldierInventoryMessage;
import com.stevesarmy.network.RecallPacket;
import com.stevesarmy.network.SetFireTeamPacket;
import com.stevesarmy.network.SetSoldierConfigPacket;
import com.stevesarmy.network.SquadStatusSyncPacket;
import com.stevesarmy.squad.FireDiscipline;
import com.stevesarmy.squad.FireTeam;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class SquadCommandScreen extends Screen {
    private static final int ROW_HEIGHT = 14;
    private static final int PANEL_LEFT = 8;
    private static final int ROW_START_Y = 22;
    private static final int FOOTER_HEIGHT = 114;
    private static final int LIST_FOOTER_GAP = 8;

    private static final int COL_HEALTH_WIDTH = 40;
    private static final int COL_FT_WIDTH = 24;
    private static final int COL_NAME_WIDTH = 72;
    private static final int COL_GUN_WIDTH = 80;
    private static final int COL_AMMO_WIDTH = 32;
    private static final int COL_DIST_WIDTH = 28;
    private static final int COL_DISC_WIDTH = 24;
    private static final int COL_INV_WIDTH = 58;
    private static final int COL_RECALL_WIDTH = 50;
    private static final int COL_SPACING = 4;

    private List<SoldierRow> rows = new ArrayList<>();
    private int teamCount = 2;
    private int scrollOffset;

    private static class SoldierRow {
        final UUID entityId;
        final int entityIntId;
        String name;
        float health;
        float maxHealth;
        int totalAmmo;
        ItemStack gunStack;
        FireDiscipline discipline;
        FireTeam fireTeam;
        double distance;
        int recallTicks;
        int invButtonX;
        int recallButtonX;

        final OpenInventoryButton invButton;

        SoldierRow(SquadStatusSyncPacket.SoldierStatusEntry entry) {
            this.entityId = entry.entityId;
            this.entityIntId = entry.entityIntId;
            this.name = entry.name;
            this.health = entry.health;
            this.maxHealth = entry.maxHealth;
            this.totalAmmo = entry.totalAmmo;
            this.gunStack = entry.gunStack;
            this.discipline = entry.getFireDiscipline();
            this.fireTeam = entry.getFireTeam();
            this.distance = entry.distance;
            this.recallTicks = entry.recallTicks;
            this.invButton = new OpenInventoryButton(() -> {
                NetworkHandler.INSTANCE.sendToServer(new OpenSoldierInventoryMessage(entityIntId));
            });
        }

        void update(SquadStatusSyncPacket.SoldierStatusEntry entry, net.minecraft.client.gui.Font font) {
            this.name = entry.name;
            this.health = entry.health;
            this.maxHealth = entry.maxHealth;
            this.totalAmmo = entry.totalAmmo;
            this.gunStack = entry.gunStack;
            this.discipline = entry.getFireDiscipline();
            this.fireTeam = entry.getFireTeam();
            this.distance = entry.distance;
            this.recallTicks = entry.recallTicks;
            this.invButton.setInRange(distance <= 20.0);
        }
    }

    public SquadCommandScreen() {
        super(Component.literal("Squad Command"));
    }

    @Override
    protected void init() {
        super.init();
        rebuildRows();
    }

    private void rebuildRows() {
        rows.clear();
        for (SquadStatusSyncPacket.SoldierStatusEntry entry : ClientSquadData.INSTANCE.getAllEntries()) {
            rows.add(new SoldierRow(entry));
        }
        sortRows();
        clampScrollOffset();
    }

    private void updateRows() {
        List<SquadStatusSyncPacket.SoldierStatusEntry> entries = ClientSquadData.INSTANCE.getAllEntries();
        Map<UUID, SoldierRow> existingRows = new HashMap<>();
        for (SoldierRow row : rows) {
            existingRows.put(row.entityId, row);
        }

        List<SoldierRow> updatedRows = new ArrayList<>(entries.size());
        for (SquadStatusSyncPacket.SoldierStatusEntry entry : entries) {
            SoldierRow row = existingRows.get(entry.entityId);
            if (row == null) {
                row = new SoldierRow(entry);
            } else {
                row.update(entry, font);
            }
            updatedRows.add(row);
        }
        rows = updatedRows;
        sortRows();
        clampScrollOffset();
    }

    private void sortRows() {
        rows.sort(Comparator
            .comparingInt((SoldierRow row) -> row.fireTeam.ordinal())
            .thenComparing(row -> row.name, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(row -> row.entityId));
    }

    @Override
    public void tick() {
        super.tick();
        teamCount = FireTeamScopeState.INSTANCE.getTeamCount();
        updateRows();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        buttonHandlers.clear();

        graphics.drawString(font, Component.literal("Squad Command"), PANEL_LEFT + 4, 6, 0xFFFFFFFF, false);

        int contentX = PANEL_LEFT + 4;
        int contentWidth = width - 2 * PANEL_LEFT - 8;
        int listBottom = getListBottom();
        int visibleRows = getVisibleRowCount();

        graphics.enableScissor(contentX, ROW_START_Y, contentX + contentWidth, listBottom);
        int lastVisibleRow = Math.min(rows.size(), scrollOffset + visibleRows);
        for (int i = scrollOffset; i < lastVisibleRow; i++) {
            SoldierRow row = rows.get(i);
            int y = ROW_START_Y + (i - scrollOffset) * ROW_HEIGHT;

            int rowBg = (i % 2 == 0) ? 0x44000000 : 0x22000000;
            graphics.fill(contentX, y, contentX + contentWidth, y + ROW_HEIGHT, rowBg);

            int x = contentX;

            x = drawHealthBar(graphics, x, y, row.health, row.maxHealth);
            x += COL_SPACING;

            x = drawFireTeamBadge(graphics, x, y, row.fireTeam);
            x += COL_SPACING;

            x = drawName(graphics, x, y, row.name);
            x += COL_SPACING;

            x = drawGunInfo(graphics, x, y, row.gunStack);
            x += COL_SPACING;

            x = drawAmmoInfo(graphics, x, y, row.totalAmmo);
            x += COL_SPACING;

            x = drawDistance(graphics, x, y, row.distance);
            x += COL_SPACING;

            x = drawDiscipline(graphics, x, y, row.discipline);
            x += COL_SPACING;

            row.invButtonX = x;
            row.invButton.render(graphics, font, x, y, row.invButton.getWidth(), row.invButton.getHeight(), mouseX - x, mouseY - y);
            x += row.invButton.getWidth() + COL_SPACING;

            row.recallButtonX = x;
            drawRecallButton(graphics, x, y, row.recallTicks, mouseX, mouseY);
            x += COL_RECALL_WIDTH + COL_SPACING;
        }
        graphics.disableScissor();

        drawScrollbar(graphics, contentX + contentWidth - 3, ROW_START_Y, listBottom, visibleRows);

        int footerY = getFooterY();
        graphics.drawString(font, Component.literal("-- Squad Settings --  NPCs: " + rows.size()), contentX, footerY, 0xFFAAAAAA, false);

        int btnY = footerY + 12;
        int btnWidth = 60;
        int btnHeight = 12;

        graphics.drawString(font, Component.literal("Discipline:"), contentX, btnY + 2, 0xFFCCCCCC, false);
        int dx = contentX + font.width("Discipline:") + 4;

        drawButton(graphics, "Standard", dx, btnY, btnWidth, btnHeight, mouseX, mouseY,
            () -> sendSquadWideConfig(SetSoldierConfigPacket.ConfigType.FIRE_DISCIPLINE, FireDiscipline.STANDARD.ordinal()));
        dx += btnWidth + 4;
        drawButton(graphics, "Conserve", dx, btnY, btnWidth, btnHeight, mouseX, mouseY,
            () -> sendSquadWideConfig(SetSoldierConfigPacket.ConfigType.FIRE_DISCIPLINE, FireDiscipline.CONSERVE.ordinal()));
        dx += btnWidth + 4;
        drawButton(graphics, "Suppress", dx, btnY, btnWidth, btnHeight, mouseX, mouseY,
            () -> sendSquadWideConfig(SetSoldierConfigPacket.ConfigType.FIRE_DISCIPLINE, FireDiscipline.SUPPRESSIVE.ordinal()));

        btnY += btnHeight + 6;
        graphics.drawString(font, Component.literal("-- Fire Teams --"), contentX, btnY, 0xFFAAAAAA, false);
        btnY += 12;

        graphics.drawString(font, Component.literal(getFireTeamCountLabel()), contentX, btnY + 2, 0xFFCCCCCC, false);
        btnY += 14;

        graphics.drawString(font, Component.literal("Teams: " + teamCount), contentX, btnY + 2, 0xFFCCCCCC, false);
        dx = contentX + font.width("Teams: " + teamCount) + 4;
        drawButton(graphics, "-", dx, btnY, 14, btnHeight, mouseX, mouseY, () -> {
            if (teamCount > 1) {
                teamCount--;
                NetworkHandler.INSTANCE.sendToServer(SetFireTeamPacket.setTeamCount(teamCount));
            }
        });
        dx += 18;
        drawButton(graphics, "+", dx, btnY, 14, btnHeight, mouseX, mouseY, () -> {
            if (teamCount < 4) {
                teamCount++;
                NetworkHandler.INSTANCE.sendToServer(SetFireTeamPacket.setTeamCount(teamCount));
            }
        });
        dx += 22;
        drawButton(graphics, "Rebalance", dx, btnY, 60, btnHeight, mouseX, mouseY, () -> {
            NetworkHandler.INSTANCE.sendToServer(SetFireTeamPacket.rebalance());
        });

        btnY += btnHeight + 10;
        graphics.drawString(font, Component.literal("-- Spacing --"), contentX, btnY, 0xFFAAAAAA, false);
        btnY += 12;

        double spacing = StevesArmyConfig.getSpacingDistance();
        String spacingLabel = String.format("%.1f", spacing);
        graphics.drawString(font, Component.literal("Distance: " + spacingLabel + "m"), contentX, btnY + 2, 0xFFCCCCCC, false);
        dx = contentX + font.width("Distance: " + spacingLabel + "m") + 4;
        drawButton(graphics, "-0.5", dx, btnY, 30, btnHeight, mouseX, mouseY, () -> {
            double newVal = Math.max(1.0, StevesArmyConfig.getSpacingDistance() - 0.5);
            for (SoldierRow row : rows) {
                NetworkHandler.INSTANCE.sendToServer(new SetSoldierConfigPacket(row.entityId, SetSoldierConfigPacket.ConfigType.SPACING_DISTANCE, (int)Math.round(newVal * 10)));
            }
        });
        dx += 34;
        drawButton(graphics, "+0.5", dx, btnY, 30, btnHeight, mouseX, mouseY, () -> {
            double newVal = Math.min(10.0, StevesArmyConfig.getSpacingDistance() + 0.5);
            for (SoldierRow row : rows) {
                NetworkHandler.INSTANCE.sendToServer(new SetSoldierConfigPacket(row.entityId, SetSoldierConfigPacket.ConfigType.SPACING_DISTANCE, (int)Math.round(newVal * 10)));
            }
        });

        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private String getFireTeamCountLabel() {
        StringBuilder label = new StringBuilder("Members: ");
        FireTeam[] teams = FireTeam.values();
        for (int i = 0; i < teamCount; i++) {
            if (i > 0) {
                label.append("  ");
            }
            FireTeam team = teams[i + 1];
            int count = 0;
            for (SoldierRow row : rows) {
                if (row.fireTeam == team) {
                    count++;
                }
            }
            label.append(team.getShortName()).append(": ").append(count);
        }
        return label.toString();
    }

    private int drawHealthBar(GuiGraphics graphics, int x, int y, float health, float maxHealth) {
        int barWidth = COL_HEALTH_WIDTH;
        int barHeight = 8;
        float ratio = Math.min(1.0f, health / Math.max(1.0f, maxHealth));

        graphics.fill(x, y + 2, x + barWidth, y + 2 + barHeight, 0xFF333333);
        int barColor = ratio > 0.5f ? 0xFF44AA44 : (ratio > 0.25f ? 0xFFCCAA44 : 0xFFCC4444);
        int barEnd = x + (int)(barWidth * ratio);
        if (barEnd > x) {
            graphics.fill(x, y + 2, barEnd, y + 2 + barHeight, barColor);
        }

        int pct = (int)(ratio * 100);
        String pctStr = pct + "%";
        graphics.drawString(font, Component.literal(pctStr), x + barWidth - font.width(pctStr) - 1, y + 2, 0xFFFFFFFF, false);
        return x + barWidth;
    }

    private int drawFireTeamBadge(GuiGraphics graphics, int x, int y, FireTeam fireTeam) {
        String ftLabel = fireTeam.getShortName();
        int ftColor = switch (fireTeam) {
            case ALPHA -> 0xFFFF5555;
            case BRAVO -> 0xFF5555FF;
            case CHARLIE -> 0xFF55FF55;
            case DELTA -> 0xFFFFFF55;
            default -> 0xFFFFFFFF;
        };
        String badge = "[" + ftLabel + "]";
        int width = font.width(badge);
        graphics.drawString(font, Component.literal(badge), x, y + 2, ftColor, false);
        return x + COL_FT_WIDTH;
    }

    private int drawName(GuiGraphics graphics, int x, int y, String name) {
        String displayName = name;
        if (font.width(name) > COL_NAME_WIDTH) {
            while (font.width(displayName + "...") > COL_NAME_WIDTH && displayName.length() > 0) {
                displayName = displayName.substring(0, displayName.length() - 1);
            }
            displayName = displayName + "...";
        }
        graphics.drawString(font, Component.literal(displayName), x, y + 2, 0xFFCCCCCC, false);
        return x + COL_NAME_WIDTH;
    }

    private int drawGunInfo(GuiGraphics graphics, int x, int y, ItemStack gunStack) {
        // Resolve the hover name on this client so TaCZ's client-only getName() is used.
        String gunStr = gunStack.isEmpty() ? "-" : gunStack.getHoverName().getString();
        if (font.width(gunStr) > COL_GUN_WIDTH - 12) {
            while (font.width(gunStr + "...") > COL_GUN_WIDTH - 12 && gunStr.length() > 0) {
                gunStr = gunStr.substring(0, gunStr.length() - 1);
            }
            gunStr = gunStr + "...";
        }
        graphics.drawString(font, Component.literal("🔫 " + gunStr), x, y + 2, 0xFF888888, false);
        return x + COL_GUN_WIDTH;
    }

    private int drawAmmoInfo(GuiGraphics graphics, int x, int y, int totalAmmo) {
        String ammoStr = String.valueOf(totalAmmo);
        graphics.drawString(font, Component.literal("◆" + ammoStr), x, y + 2, 0xFFAAAAAA, false);
        return x + COL_AMMO_WIDTH;
    }

    private int drawDistance(GuiGraphics graphics, int x, int y, double distance) {
        String distStr = String.format("%.1fm", distance);
        int color = distance <= 20.0 ? 0xFF55FF55 : (distance <= 40.0 ? 0xFFFFFF55 : 0xFFFF5555);
        graphics.drawString(font, Component.literal(distStr), x, y + 2, color, false);
        return x + COL_DIST_WIDTH;
    }

    private int drawDiscipline(GuiGraphics graphics, int x, int y, FireDiscipline discipline) {
        String discLabel = switch (discipline) {
            case STANDARD -> "STD";
            case CONSERVE -> "CON";
            case SUPPRESSIVE -> "SUP";
        };
        graphics.drawString(font, Component.literal(discLabel), x, y + 2, 0xFF777777, false);
        return x + COL_DISC_WIDTH;
    }

    private void drawRecallButton(GuiGraphics graphics, int x, int y, int recallTicks, int mouseX, int mouseY) {
        boolean isRecalling = recallTicks > 0;
        int w = COL_RECALL_WIDTH;
        int h = 12;
        String label = isRecalling ? ((recallTicks + 19) / 20) + "s" : "Recall";
        boolean hovered = mouseX >= x && mouseX <= x + w && mouseY >= y && mouseY <= y + h;

        int bgColor = isRecalling ? 0x88000000 : (hovered ? 0xFF444444 : 0x88000000);
        int borderColor = isRecalling ? 0xFF333333 : 0xFF555555;
        int textColor = isRecalling ? 0xFF888888 : 0xFFAAAAAA;

        graphics.fill(x, y, x + w, y + h, bgColor);
        graphics.fill(x, y, x + w, y + 1, borderColor);
        graphics.fill(x, y + h - 1, x + w, y + h, borderColor);
        graphics.fill(x, y, x + 1, y + h, borderColor);
        graphics.fill(x + w - 1, y, x + w, y + h, borderColor);
        graphics.drawString(font, Component.literal(label), x + 2, y + 2, textColor, false);
    }

    private void drawButton(GuiGraphics graphics, String label, int x, int y, int w, int h, int mx, int my, Runnable onClick) {
        boolean hovered = mx >= x && mx <= x + w && my >= y && my <= y + h;
        graphics.fill(x, y, x + w, y + h, hovered ? 0xFF444444 : 0x88000000);
        graphics.fill(x, y, x + w, y + 1, 0xFF555555);
        graphics.fill(x, y + h - 1, x + w, y + h, 0xFF555555);
        graphics.fill(x, y, x + 1, y + h, 0xFF555555);
        graphics.fill(x + w - 1, y, x + w, y + h, 0xFF555555);
        graphics.drawString(font, Component.literal(label), x + 2, y + 2, 0xFFAAAAAA, false);
        buttonHandlers.add(new ButtonHitbox(x, y, w, h, onClick));
    }

    private static class ButtonHitbox {
        final int x, y, w, h;
        final Runnable onClick;
        ButtonHitbox(int x, int y, int w, int h, Runnable onClick) {
            this.x = x; this.y = y; this.w = w; this.h = h; this.onClick = onClick;
        }
    }

    private final List<ButtonHitbox> buttonHandlers = new ArrayList<>();

    private int getFooterY() {
        return Math.max(ROW_START_Y + ROW_HEIGHT, height - FOOTER_HEIGHT);
    }

    private int getListBottom() {
        return Math.max(ROW_START_Y + ROW_HEIGHT, getFooterY() - LIST_FOOTER_GAP);
    }

    private int getVisibleRowCount() {
        return Math.max(1, (getListBottom() - ROW_START_Y) / ROW_HEIGHT);
    }

    private int getMaxScrollOffset() {
        return Math.max(0, rows.size() - getVisibleRowCount());
    }

    private void clampScrollOffset() {
        scrollOffset = Math.max(0, Math.min(scrollOffset, getMaxScrollOffset()));
    }

    private void drawScrollbar(GuiGraphics graphics, int x, int top, int bottom, int visibleRows) {
        if (rows.size() <= visibleRows) return;

        int trackHeight = bottom - top;
        int thumbHeight = Math.max(8, trackHeight * visibleRows / rows.size());
        int thumbY = top + (trackHeight - thumbHeight) * scrollOffset / getMaxScrollOffset();
        graphics.fill(x, top, x + 2, bottom, 0x66000000);
        graphics.fill(x, thumbY, x + 2, thumbY + thumbHeight, 0xFF777777);
    }

    private void sendSquadWideConfig(SetSoldierConfigPacket.ConfigType type, int value) {
        for (SoldierRow row : rows) {
            NetworkHandler.INSTANCE.sendToServer(new SetSoldierConfigPacket(row.entityId, type, value));
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        for (ButtonHitbox bh : buttonHandlers) {
            if (mouseX >= bh.x && mouseX <= bh.x + bh.w && mouseY >= bh.y && mouseY <= bh.y + bh.h) {
                bh.onClick.run();
                return true;
            }
        }

        if (mouseY >= ROW_START_Y && mouseY < getListBottom()) {
            int rowIndex = scrollOffset + (int) ((mouseY - ROW_START_Y) / ROW_HEIGHT);
            if (rowIndex < rows.size()) {
                SoldierRow row = rows.get(rowIndex);
                int rowY = ROW_START_Y + (rowIndex - scrollOffset) * ROW_HEIGHT;
                int localMouseY = (int)(mouseY - rowY);

                if (tryClickInventoryButton(row, mouseX, localMouseY)) return true;
                if (tryClickRecallButton(mouseX, row.recallButtonX, localMouseY, row.entityIntId, row.recallTicks)) return true;
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private boolean tryClickInventoryButton(SoldierRow row, double mouseX, int localMouseY) {
        return row.invButton.mouseClicked(mouseX - row.invButtonX, localMouseY, 0);
    }

    private boolean tryClickRecallButton(double mouseX, int buttonX, int localMouseY, int soldierId, int recallTicks) {
        if (recallTicks > 0) return false;
        if (mouseX >= buttonX && mouseX <= buttonX + COL_RECALL_WIDTH && localMouseY >= 0 && localMouseY <= 12) {
            NetworkHandler.INSTANCE.sendToServer(new RecallPacket(soldierId));
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (mouseY >= ROW_START_Y && mouseY < getListBottom() && getMaxScrollOffset() > 0) {
            scrollOffset -= (int) Math.signum(delta);
            clampScrollOffset();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
