package com.stevesarmy.client.screen;

import com.stevesarmy.client.ClientSquadData;
import com.stevesarmy.client.FireTeamScopeState;
import com.stevesarmy.client.screen.widget.RoleDropdownWidget;
import com.stevesarmy.entity.SoldierRole;
import com.stevesarmy.network.BulkGarrisonPacket;
import com.stevesarmy.network.NetworkHandler;
import com.stevesarmy.network.DismissSoldierPacket;
import com.stevesarmy.network.OpenSoldierInventoryMessage;
import com.stevesarmy.network.RecallPacket;
import com.stevesarmy.network.SetFireTeamPacket;
import com.stevesarmy.network.SetSoldierConfigPacket;
import com.stevesarmy.network.SetSoldierRoleByUUIDPacket;
import com.stevesarmy.network.SetResupplyConfigPacket;
import com.stevesarmy.network.SquadStatusSyncPacket;
import com.stevesarmy.squad.FireDiscipline;
import com.stevesarmy.squad.FireTeam;
import com.stevesarmy.squad.ResupplyConfig;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class SquadCommandScreen extends Screen {
    private static final int ROW_HEIGHT = 22;
    private static final int PANEL_LEFT = 8;
    private static final int ROW_START_Y = 22;
    private static final int FOOTER_HEIGHT = 80;
    private static final int LIST_FOOTER_GAP = 8;
    private static final int VANILLA_BUTTON_HEIGHT = 20;
    private static final int BUTTON_TEXT_Y_OFFSET = 6;

    private static final int COL_HEALTH_WIDTH = 40;
    private static final int COL_FT_WIDTH = 24;
    private static final int COL_AMMO_WIDTH = 32;
    private static final int COL_DIST_WIDTH = 50;
    private static final int COL_DISC_WIDTH = 24;
    private static final int COL_ROLE_WIDTH = 70;
    private static final int COL_SPACING = 4;
    private static final int ACTION_BUTTON_GAP = 2;
    private static final int ACTION_BUTTON_SIZE = 20;
    private static final int ACTION_STRIP_WIDTH = ACTION_BUTTON_SIZE * 3 + ACTION_BUTTON_GAP * 2;

    private static final ItemStack INVENTORY_ICON = new ItemStack(Items.CHEST);
    private static final ItemStack RECALL_ICON = new ItemStack(Items.ENDER_PEARL);
    private static final ItemStack DISMISS_ICON = new ItemStack(Items.BARRIER);

    private List<SoldierRow> rows = new ArrayList<>();
    private int teamCount = 2;
    private int scrollOffset;
    private Component hoveredActionTooltip;
    private Tab activeTab = Tab.SQUAD;
    private int activeRoleDropdownRow = -1;
    private int activeRoleDropdownX;
    private int activeRoleDropdownY;
    private RoleDropdownWidget activeRoleDropdownWidget;
    private int bulkRoleOrdinal = SoldierRole.RIFLEMAN.ordinal();
    private com.stevesarmy.squad.ResupplyConfig draftConfig = com.stevesarmy.squad.ResupplyConfig.DEFAULT;

    private enum Tab {
        SQUAD,
        GARRISON,
        RESUPPLY
    }

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
        int roleOrdinal;
        double distance;
        int recallTicks;
        boolean loaded;
        int invButtonX;
        int recallButtonX;
        int dismissButtonX;
        int roleColumnX;

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
            this.roleOrdinal = entry.roleOrdinal;
            this.distance = entry.distance;
            this.recallTicks = entry.recallTicks;
            this.loaded = entry.loaded;
        }

        void update(SquadStatusSyncPacket.SoldierStatusEntry entry, net.minecraft.client.gui.Font font) {
            this.name = entry.name;
            this.health = entry.health;
            this.maxHealth = entry.maxHealth;
            this.totalAmmo = entry.totalAmmo;
            this.gunStack = entry.gunStack;
            this.discipline = entry.getFireDiscipline();
            this.fireTeam = entry.getFireTeam();
            this.roleOrdinal = entry.roleOrdinal;
            this.distance = entry.distance;
            this.recallTicks = entry.recallTicks;
            this.loaded = entry.loaded;
        }

        SoldierRole getRole() {
            return SoldierRole.values()[roleOrdinal % SoldierRole.values().length];
        }
    }

    public SquadCommandScreen() {
        super(Component.literal("Squad Command"));
    }

    @Override
    protected void init() {
        super.init();
        rebuildRows();
        rebuildFooterButtons();
    }

    private boolean matchesTab(FireTeam fireTeam) {
        return switch (activeTab) {
            case GARRISON -> fireTeam == FireTeam.GARRISON;
            case RESUPPLY -> false;
            default -> fireTeam != FireTeam.GARRISON;
        };
    }

    private void rebuildTabButtons() {
        int tabWidth = 60;
        int tabHeight = 14;
        int x = width - PANEL_LEFT - tabWidth * 3 - 8;
        int y = 6;

        Button squadTab = Button.builder(Component.literal("Squad"), button -> switchTab(Tab.SQUAD))
            .bounds(x, y, tabWidth, tabHeight).build();
        squadTab.active = activeTab != Tab.SQUAD;
        addRenderableWidget(squadTab);

        x += tabWidth + 4;
        Button garrisonTab = Button.builder(Component.literal("Garrison"), button -> switchTab(Tab.GARRISON))
            .bounds(x, y, tabWidth, tabHeight).build();
        garrisonTab.active = activeTab != Tab.GARRISON;
        addRenderableWidget(garrisonTab);

        x += tabWidth + 4;
        Button resupplyTab = Button.builder(Component.literal("Resupply"), button -> switchTab(Tab.RESUPPLY))
            .bounds(x, y, tabWidth, tabHeight).build();
        resupplyTab.active = activeTab != Tab.RESUPPLY;
        addRenderableWidget(resupplyTab);
    }

    private void switchTab(Tab tab) {
        if (activeTab == tab) return;
        activeTab = tab;
        closeRoleDropdown();
        if (tab == Tab.RESUPPLY) {
            draftConfig = ClientSquadData.INSTANCE.getResupplyConfig();
        }
        rebuildRows();
        rebuildFooterButtons();
    }
    private void rebuildFooterButtons() {
        clearWidgets();

        if (activeTab == Tab.RESUPPLY) {
            rebuildResupplyButtons();
            rebuildTabButtons();
            return;
        }

        int contentX = PANEL_LEFT + 4;
        int footerY = getFooterY();
        int btnY = footerY + 14;
        int btnHeight = VANILLA_BUTTON_HEIGHT;
        int btnWidth = 60;
        int x = contentX + font.width("Discipline:") + 4;

        addRenderableWidget(Button.builder(Component.literal("Standard"), button ->
            sendSquadWideConfig(SetSoldierConfigPacket.ConfigType.FIRE_DISCIPLINE, FireDiscipline.STANDARD.ordinal()))
            .bounds(x, btnY, btnWidth, btnHeight).build());
        x += btnWidth + 4;
        addRenderableWidget(Button.builder(Component.literal("Conserve"), button ->
            sendSquadWideConfig(SetSoldierConfigPacket.ConfigType.FIRE_DISCIPLINE, FireDiscipline.CONSERVE.ordinal()))
            .bounds(x, btnY, btnWidth, btnHeight).build());
        x += btnWidth + 4;
        addRenderableWidget(Button.builder(Component.literal("Suppress"), button ->
            sendSquadWideConfig(SetSoldierConfigPacket.ConfigType.FIRE_DISCIPLINE, FireDiscipline.SUPPRESSIVE.ordinal()))
            .bounds(x, btnY, btnWidth, btnHeight).build());

        x = contentX + font.width("Discipline:") + 4;
        int roleApplyX = x + 3 * (btnWidth + 4) + 10;
        int applyWidth = 80;
        Button applyRoleButton = Button.builder(Component.literal("Apply Role"), button -> {
            SoldierRole role = SoldierRole.values()[bulkRoleOrdinal % SoldierRole.values().length];
            for (SoldierRow row : rows) {
                NetworkHandler.INSTANCE.sendToServer(new SetSoldierRoleByUUIDPacket(row.entityId, role));
            }
        }).bounds(roleApplyX, btnY, applyWidth, btnHeight).build();
        addRenderableWidget(applyRoleButton);

        int fireTeamRowY = footerY + 58;
        x = contentX + font.width("Teams: 4") + 4;
        addRenderableWidget(Button.builder(Component.literal("-"), button -> {
            if (teamCount > 1) {
                teamCount--;
                NetworkHandler.INSTANCE.sendToServer(SetFireTeamPacket.setTeamCount(teamCount));
                rebuildFooterButtons();
            }
        }).bounds(x, fireTeamRowY, 20, btnHeight).build());
        x += 24;
        addRenderableWidget(Button.builder(Component.literal("+"), button -> {
            if (teamCount < 4) {
                teamCount++;
                NetworkHandler.INSTANCE.sendToServer(SetFireTeamPacket.setTeamCount(teamCount));
                rebuildFooterButtons();
            }
        }).bounds(x, fireTeamRowY, 20, btnHeight).build());
        x += 24;
        addRenderableWidget(Button.builder(Component.literal("Rebalance"), button ->
            NetworkHandler.INSTANCE.sendToServer(SetFireTeamPacket.rebalance()))
            .bounds(x, fireTeamRowY, 60, btnHeight).build());
        x += 60 + 4;

        FireTeam scope = FireTeamScopeState.INSTANCE.getCurrentScope();
        boolean canGarrisonize = scope != FireTeam.ALL && scope != FireTeam.GARRISON;
        Button toGarrison = Button.builder(Component.literal("To Garrison"), button ->
            NetworkHandler.INSTANCE.sendToServer(new BulkGarrisonPacket(BulkGarrisonPacket.Mode.TO_GARRISON,
                FireTeamScopeState.INSTANCE.getCurrentScope())))
            .bounds(x, fireTeamRowY, 70, btnHeight).build();
        toGarrison.active = canGarrisonize;
        addRenderableWidget(toGarrison);
        x += 70 + 4;
        Button toSquad = Button.builder(Component.literal("To Squad"), button ->
            NetworkHandler.INSTANCE.sendToServer(new BulkGarrisonPacket(BulkGarrisonPacket.Mode.TO_SQUAD,
                FireTeamScopeState.INSTANCE.getCurrentScope())))
            .bounds(x, fireTeamRowY, 70, btnHeight).build();
        addRenderableWidget(toSquad);

        rebuildTabButtons();
    }

    private void rebuildResupplyButtons() {
        int btnHeight = VANILLA_BUTTON_HEIGHT;
        int btnW = 20;
        int contentX = PANEL_LEFT + 4;
        int rowY = ROW_START_Y + 6;
        int spacing = 26;

        String[] labels = {"Ammo Threshold", "Heal Threshold", "Resupply Ammo To", "Resupply Heals To"};
        int[] values = {
            draftConfig.ammoThreshold(),
            draftConfig.healingThreshold(),
            draftConfig.resupplyToAmmo(),
            draftConfig.resupplyToHeals()
        };
        int labelMaxW = font.width("Resupply Heals To");

        for (int i = 0; i < 4; i++) {
            int labelEnd = contentX + labelMaxW + 8;
            int minusX = labelEnd;
            int valueX = minusX + btnW + 6;
            int plusX = valueX + 30;
            final int idx = i;
            addRenderableWidget(Button.builder(Component.literal("-"), b -> {
                ResupplyConfig cfg = draftConfig;
                draftConfig = switch (idx) {
                    case 0 -> cfg.withAmmoThreshold(Math.max(0, cfg.ammoThreshold() - 5));
                    case 1 -> cfg.withHealingThreshold(Math.max(0, cfg.healingThreshold() - 1));
                    case 2 -> cfg.withResupplyToAmmo(Math.max(0, cfg.resupplyToAmmo() - 5));
                    case 3 -> cfg.withResupplyToHeals(Math.max(0, cfg.resupplyToHeals() - 1));
                    default -> cfg;
                };
                NetworkHandler.INSTANCE.sendToServer(new SetResupplyConfigPacket(draftConfig));
                rebuildFooterButtons();
            }).bounds(minusX, rowY, btnW, btnHeight).build());
            addRenderableWidget(Button.builder(Component.literal("+"), b -> {
                ResupplyConfig cfg = draftConfig;
                draftConfig = switch (idx) {
                    case 0 -> cfg.withAmmoThreshold(cfg.ammoThreshold() + 5);
                    case 1 -> cfg.withHealingThreshold(cfg.healingThreshold() + 1);
                    case 2 -> cfg.withResupplyToAmmo(cfg.resupplyToAmmo() + 5);
                    case 3 -> cfg.withResupplyToHeals(cfg.resupplyToHeals() + 1);
                    default -> cfg;
                };
                NetworkHandler.INSTANCE.sendToServer(new SetResupplyConfigPacket(draftConfig));
                rebuildFooterButtons();
            }).bounds(plusX, rowY, btnW, btnHeight).build());
            rowY += spacing;
        }
    }

    private void rebuildRows() {
        rows.clear();
        for (SquadStatusSyncPacket.SoldierStatusEntry entry : ClientSquadData.INSTANCE.getAllEntries()) {
            if (matchesTab(entry.getFireTeam())) {
                rows.add(new SoldierRow(entry));
            }
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
            if (!matchesTab(entry.getFireTeam())) continue;
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
        if (activeTab != Tab.RESUPPLY) {
            updateRows();
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        hoveredActionTooltip = null;

        graphics.drawString(font, Component.literal("Squad Command"), PANEL_LEFT + 4, 6, 0xFFFFFFFF, false);

        if (activeTab == Tab.RESUPPLY) {
            renderResupplyPanel(graphics);
            super.render(graphics, mouseX, mouseY, partialTick);
            return;
        }

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

            int actionX = contentX + contentWidth - ACTION_STRIP_WIDTH;
            int x = contentX;

            x = drawHealthBar(graphics, x, y, row.health, row.maxHealth);
            x += COL_SPACING;

            x = drawFireTeamBadge(graphics, x, y, row.fireTeam);
            x += COL_SPACING;

            int fixedColsWidth = COL_AMMO_WIDTH + COL_SPACING
                + COL_DIST_WIDTH + COL_SPACING
                + COL_DISC_WIDTH + COL_SPACING
                + COL_ROLE_WIDTH + COL_SPACING;
            int flexibleWidth = Math.max(0, actionX - x - COL_SPACING - fixedColsWidth);
            int nameWidth = flexibleWidth * 2 / 5;
            int gunWidth = flexibleWidth - nameWidth;

            x = drawName(graphics, x, y, row.name, nameWidth);
            x += COL_SPACING;

            x = drawGunInfo(graphics, x, y, row.gunStack, gunWidth);
            x += COL_SPACING;

            x = drawAmmoInfo(graphics, x, y, row.totalAmmo);
            x += COL_SPACING;

            x = drawDistance(graphics, x, y, row.distance);
            x += COL_SPACING;

            x = drawDiscipline(graphics, x, y, row.discipline);
            x += COL_SPACING;

            row.roleColumnX = x;
            RoleDropdownWidget roleButton = new RoleDropdownWidget(row.getRole(), ignored -> { });
            roleButton.render(graphics, font, x, y + 4, COL_ROLE_WIDTH, ROW_HEIGHT,
                mouseX - x, mouseY - y - 4);
            x += COL_ROLE_WIDTH + COL_SPACING;

            row.invButtonX = actionX;
            row.recallButtonX = actionX + ACTION_BUTTON_SIZE + ACTION_BUTTON_GAP;
            row.dismissButtonX = row.recallButtonX + ACTION_BUTTON_SIZE + ACTION_BUTTON_GAP;
            int buttonY = y + 1;

            drawIconButton(graphics, row.invButtonX, buttonY, INVENTORY_ICON, row.loaded && row.distance <= 20.0,
                false, Component.literal("Open inventory"), mouseX, mouseY);
            String recallLabel = row.recallTicks > 0 ? "Recall: " + ((row.recallTicks + 19) / 20) + "s" : "Recall soldier";
            drawIconButton(graphics, row.recallButtonX, buttonY, RECALL_ICON, row.recallTicks <= 0,
                false, Component.literal(recallLabel), mouseX, mouseY);
            drawIconButton(graphics, row.dismissButtonX, buttonY, DISMISS_ICON, true,
                true, Component.literal("Dismiss soldier"), mouseX, mouseY);
        }
        graphics.disableScissor();

        drawScrollbar(graphics, contentX + contentWidth - 3, ROW_START_Y, listBottom, visibleRows);

        int footerY = getFooterY();
        graphics.drawString(font, Component.literal("-- Squad Settings --  NPCs: " + rows.size()), contentX, footerY, 0xFFAAAAAA, false);

        int btnY = footerY + 14;
        graphics.drawString(font, Component.literal("Discipline:"), contentX, btnY + BUTTON_TEXT_Y_OFFSET, 0xFFCCCCCC, false);

        int bulkRoleLabelX = contentX + font.width("Discipline:") + 4 + 3 * (60 + 4) + 10;
        String bulkRoleName = SoldierRole.values()[bulkRoleOrdinal % SoldierRole.values().length].getDisplayName().getString();
        graphics.drawString(font, Component.literal("Role: " + bulkRoleName), bulkRoleLabelX, btnY + BUTTON_TEXT_Y_OFFSET, 0xFFCCCCCC, false);

        btnY += 30;
        String membersLabel = getFireTeamCountLabel();
        graphics.drawString(font, Component.literal("-- Fire Teams --  " + membersLabel), contentX, btnY, 0xFFAAAAAA, false);
        int fireTeamRowY = footerY + 58;
        graphics.drawString(font, Component.literal("Teams: " + teamCount), contentX, fireTeamRowY + BUTTON_TEXT_Y_OFFSET, 0xFFCCCCCC, false);

        super.render(graphics, mouseX, mouseY, partialTick);

        if (activeRoleDropdownWidget != null && activeRoleDropdownRow >= 0 && activeRoleDropdownRow < rows.size()) {
            SoldierRow activeRow = rows.get(activeRoleDropdownRow);
            int dropItemHeight = 12;
            int dropBottom = activeRoleDropdownY + dropItemHeight * (1 + SoldierRole.values().length);
            graphics.enableScissor(activeRoleDropdownX, activeRoleDropdownY,
                activeRoleDropdownX + COL_ROLE_WIDTH, Math.min(height, dropBottom));
            activeRoleDropdownWidget.setRole(activeRow.getRole());
            activeRoleDropdownWidget.render(graphics, font, activeRoleDropdownX, activeRoleDropdownY,
                COL_ROLE_WIDTH, ROW_HEIGHT, mouseX - activeRoleDropdownX, mouseY - activeRoleDropdownY);
            graphics.disableScissor();
        }

        if (hoveredActionTooltip != null) {
            graphics.renderTooltip(font, hoveredActionTooltip, mouseX, mouseY);
        }
    }

    private void openRoleDropdown(int rowIndex, int x, int y) {
        SoldierRow row = rows.get(rowIndex);
        activeRoleDropdownRow = rowIndex;
        activeRoleDropdownX = x;
        activeRoleDropdownY = y;
        activeRoleDropdownWidget = new RoleDropdownWidget(row.getRole(), role -> {
            NetworkHandler.INSTANCE.sendToServer(new SetSoldierRoleByUUIDPacket(row.entityId, role));
            row.roleOrdinal = role.ordinal();
        });
        activeRoleDropdownWidget.openDropdown();
    }

    private void closeRoleDropdown() {
        activeRoleDropdownRow = -1;
        activeRoleDropdownWidget = null;
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

        graphics.fill(x, y + 6, x + barWidth, y + 6 + barHeight, 0xFF333333);
        int barColor = ratio > 0.5f ? 0xFF44AA44 : (ratio > 0.25f ? 0xFFCCAA44 : 0xFFCC4444);
        int barEnd = x + (int)(barWidth * ratio);
        if (barEnd > x) {
            graphics.fill(x, y + 6, barEnd, y + 6 + barHeight, barColor);
        }

        int pct = (int)(ratio * 100);
        String pctStr = pct + "%";
        graphics.drawString(font, Component.literal(pctStr), x + barWidth - font.width(pctStr) - 1, y + 6, 0xFFFFFFFF, false);
        return x + barWidth;
    }

    private int drawFireTeamBadge(GuiGraphics graphics, int x, int y, FireTeam fireTeam) {
        String ftLabel = fireTeam.getShortName();
        int ftColor = switch (fireTeam) {
            case ALPHA -> 0xFFFF5555;
            case BRAVO -> 0xFF5555FF;
            case CHARLIE -> 0xFF55FF55;
            case DELTA -> 0xFFFFFF55;
            case GARRISON -> 0xFF55FFFF;
            default -> 0xFFFFFFFF;
        };
        String badge = "[" + ftLabel + "]";
        int width = font.width(badge);
        graphics.drawString(font, Component.literal(badge), x, y + 6, ftColor, false);
        return x + COL_FT_WIDTH;
    }

    private int drawName(GuiGraphics graphics, int x, int y, String name, int width) {
        String displayName = name;
        if (font.width(name) > width) {
            while (font.width(displayName + "...") > width && displayName.length() > 0) {
                displayName = displayName.substring(0, displayName.length() - 1);
            }
            displayName = displayName + "...";
        }
        graphics.drawString(font, Component.literal(displayName), x, y + 6, 0xFFCCCCCC, false);
        return x + width;
    }

    private int drawGunInfo(GuiGraphics graphics, int x, int y, ItemStack gunStack, int width) {
        String gunStr = gunStack.isEmpty() ? "-" : gunStack.getHoverName().getString();
        int textWidth = Math.max(0, width - 12);
        if (font.width(gunStr) > textWidth) {
            while (font.width(gunStr + "...") > textWidth && gunStr.length() > 0) {
                gunStr = gunStr.substring(0, gunStr.length() - 1);
            }
            gunStr = gunStr + "...";
        }
        graphics.drawString(font, Component.literal("\uD83D\uDD2B " + gunStr), x, y + 6, 0xFF888888, false);
        return x + width;
    }

    private int drawAmmoInfo(GuiGraphics graphics, int x, int y, int totalAmmo) {
        String ammoStr = String.valueOf(totalAmmo);
        graphics.drawString(font, Component.literal("\u25C6" + ammoStr), x, y + 6, 0xFFAAAAAA, false);
        return x + COL_AMMO_WIDTH;
    }

    private int drawDistance(GuiGraphics graphics, int x, int y, double distance) {
        if (distance < 0.0) {
            graphics.drawString(font, Component.literal("unloaded"), x, y + 6, 0xFFFFAA55, false);
            return x + COL_DIST_WIDTH;
        }
        String distStr = String.format("%.1fm", distance);
        int color = distance <= 20.0 ? 0xFF55FF55 : (distance <= 40.0 ? 0xFFFFFF55 : 0xFFFF5555);
        graphics.drawString(font, Component.literal(distStr), x, y + 6, color, false);
        return x + COL_DIST_WIDTH;
    }

    private int drawDiscipline(GuiGraphics graphics, int x, int y, FireDiscipline discipline) {
        String discLabel = switch (discipline) {
            case STANDARD -> "STD";
            case CONSERVE -> "CON";
            case SUPPRESSIVE -> "SUP";
        };
        graphics.drawString(font, Component.literal(discLabel), x, y + 6, 0xFF777777, false);
        return x + COL_DISC_WIDTH;
    }

    private void renderResupplyPanel(GuiGraphics graphics) {
        int contentX = PANEL_LEFT + 4;
        int rowY = ROW_START_Y + 6;
        int spacing = 26;
        int labelMaxW = font.width("Resupply Heals To");

        String[] labels = {"Ammo Threshold", "Heal Threshold", "Resupply Ammo To", "Resupply Heals To"};
        int[] values = {
            draftConfig.ammoThreshold(),
            draftConfig.healingThreshold(),
            draftConfig.resupplyToAmmo(),
            draftConfig.resupplyToHeals()
        };

        graphics.drawString(font, Component.literal("-- Resupply Settings --"), contentX, ROW_START_Y - 4, 0xFFAAAAAA, false);

        for (int i = 0; i < 4; i++) {
            graphics.drawString(font, Component.literal(labels[i]), contentX, rowY + 6, 0xFFCCCCCC, false);
            int valueX = contentX + labelMaxW + 8 + 26;
            graphics.drawString(font, Component.literal(String.valueOf(values[i])), valueX, rowY + 6, 0xFFFFFFFF, false);
            rowY += spacing;
        }

        int hintY = rowY + 4;
        graphics.drawString(font, Component.literal("Applies to all squad members and you."), contentX, hintY, 0xFF888888, false);
    }

    private void drawIconButton(GuiGraphics graphics, int x, int y, ItemStack icon, boolean enabled,
                                boolean destructive, Component tooltip, int mouseX, int mouseY) {
        Button button = Button.builder(Component.empty(), ignored -> { })
            .bounds(x, y, ACTION_BUTTON_SIZE, ACTION_BUTTON_SIZE).build();
        button.active = enabled;
        button.render(graphics, mouseX, mouseY, 0.0f);
        graphics.renderItem(icon, x + 1, y + 1);

        if (button.isHoveredOrFocused()) {
            hoveredActionTooltip = tooltip;
        }
    }

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
        if (activeRoleDropdownWidget != null && activeRoleDropdownRow >= 0 && activeRoleDropdownRow < rows.size()) {
            int i = activeRoleDropdownRow;
            SoldierRow row = rows.get(i);

            boolean clicked = activeRoleDropdownWidget.mouseClicked(
                mouseX - activeRoleDropdownX, mouseY - activeRoleDropdownY, button);
            if (clicked) {
                if (!activeRoleDropdownWidget.isDropdownOpen()) {
                    closeRoleDropdown();
                }
                return true;
            } else {
                closeRoleDropdown();
            }
        }

        if (super.mouseClicked(mouseX, mouseY, button)) return true;

        if (mouseY >= ROW_START_Y && mouseY < getListBottom() && button == 0) {
            int rowIndex = scrollOffset + (int) ((mouseY - ROW_START_Y) / ROW_HEIGHT);
            if (rowIndex < rows.size()) {
                SoldierRow row = rows.get(rowIndex);
                int rowY = ROW_START_Y + (rowIndex - scrollOffset) * ROW_HEIGHT;
                int localMouseY = (int)(mouseY - rowY);

                if (mouseX >= row.roleColumnX && mouseX < row.roleColumnX + COL_ROLE_WIDTH
                    && localMouseY >= 4 && localMouseY < 16) {
                    openRoleDropdown(rowIndex, row.roleColumnX, rowY + 4);
                    return true;
                }

                if (tryClickInventoryButton(row, mouseX, localMouseY)) return true;
                if (tryClickRecallButton(mouseX, row.recallButtonX, localMouseY, row.entityId, row.recallTicks)) return true;
                if (tryClickDismissButton(mouseX, row.dismissButtonX, localMouseY, row.entityId)) return true;
                return true;
            }
        }
        return false;
    }

    private boolean tryClickInventoryButton(SoldierRow row, double mouseX, int localMouseY) {
        if (!row.loaded || row.distance > 20.0) return false;
        if (mouseX >= row.invButtonX && mouseX < row.invButtonX + ACTION_BUTTON_SIZE
            && localMouseY >= 1 && localMouseY < 1 + ACTION_BUTTON_SIZE) {
            NetworkHandler.INSTANCE.sendToServer(new OpenSoldierInventoryMessage(row.entityIntId));
            return true;
        }
        return false;
    }

    private boolean tryClickRecallButton(double mouseX, int buttonX, int localMouseY, UUID soldierId, int recallTicks) {
        if (recallTicks > 0) return false;
        if (mouseX >= buttonX && mouseX < buttonX + ACTION_BUTTON_SIZE
            && localMouseY >= 1 && localMouseY < 1 + ACTION_BUTTON_SIZE) {
            NetworkHandler.INSTANCE.sendToServer(new RecallPacket(soldierId));
            return true;
        }
        return false;
    }

    private boolean tryClickDismissButton(double mouseX, int buttonX, int localMouseY, UUID soldierId) {
        if (mouseX >= buttonX && mouseX < buttonX + ACTION_BUTTON_SIZE
            && localMouseY >= 1 && localMouseY < 1 + ACTION_BUTTON_SIZE) {
            NetworkHandler.INSTANCE.sendToServer(new DismissSoldierPacket(soldierId));
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (activeRoleDropdownRow >= 0) {
            closeRoleDropdown();
        }
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
