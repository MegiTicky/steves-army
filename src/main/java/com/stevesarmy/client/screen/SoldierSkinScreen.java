package com.stevesarmy.client.screen;

import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.client.SoldierSkinLoader;
import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.network.NetworkHandler;
import com.stevesarmy.network.SetSkinPacket;
import com.stevesarmy.skin.SoldierSkinManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSelectionList;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Skin picker for a specific soldier, opened by right-clicking with the Skin
 * Knife. Lists the default skin, every skins-folder PNG (freshly scanned) and
 * resource-pack skins; applying sends a SetSkinPacket for server validation.
 */
@OnlyIn(Dist.CLIENT)
public class SoldierSkinScreen extends Screen {

    private static final int PANEL_WIDTH = 170;
    private static final int PREVIEW_SIZE = 96;
    private static final ResourceLocation DEFAULT_TEXTURE =
        new ResourceLocation("minecraft", "textures/entity/player/wide/steve.png");

    private final SoldierEntity soldier;
    private final List<SkinEntry> skins = new ArrayList<>();
    private SkinEntry selected;
    private SkinList list;
    private int listLeft;
    private int listTop;

    public SoldierSkinScreen(SoldierEntity soldier) {
        super(Component.literal("Select Skin"));
        this.soldier = soldier;
    }

    /** Client-only entry point so common code (SkinKnifeItem) never references client classes directly. */
    public static void open(SoldierEntity soldier) {
        Minecraft.getInstance().setScreen(new SoldierSkinScreen(soldier));
    }

    @Override
    protected void init() {
        // Fresh scan so PNGs dropped while the game is running show up immediately.
        SoldierSkinLoader.reload();

        this.skins.clear();
        this.skins.addAll(collectSkins());

        String current = this.soldier.getSkin();
        this.selected = this.skins.stream()
            .filter(s -> s.name().equals(current))
            .findFirst()
            .orElse(this.skins.get(0));

        this.listTop = Math.max(40, this.height / 2 - 80);
        int listHeight = Math.min(160, this.height - this.listTop - 70);
        this.listLeft = (this.width - (PANEL_WIDTH + 10 + PREVIEW_SIZE + 20)) / 2;

        this.list = new SkinList(this.minecraft, PANEL_WIDTH, listHeight, this.listTop, this.listTop + listHeight, 20);
        this.list.setLeftPos(this.listLeft);
        this.addRenderableWidget(this.list);
        for (SkinEntry skin : this.skins) {
            this.list.addEntry(new SkinRow(skin));
        }

        int buttonY = Math.min(this.height - 30, this.listTop + listHeight + 8);
        this.addRenderableWidget(Button.builder(Component.literal("Apply"), b -> apply())
            .bounds(this.listLeft, buttonY, 56, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("Reset"), b -> reset())
            .bounds(this.listLeft + 60, buttonY, 56, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> onClose())
            .bounds(this.listLeft + 116, buttonY, 56, 20).build());
    }

    private List<SkinEntry> collectSkins() {
        List<SkinEntry> entries = new ArrayList<>();
        entries.add(new SkinEntry("", "Default", DEFAULT_TEXTURE));

        Set<String> seen = new HashSet<>();
        for (String name : SoldierSkinManager.getSkinNames()) {
            entries.add(new SkinEntry(name, name, SoldierSkinLoader.folderTexture(name)));
            seen.add(name.toLowerCase(Locale.ROOT));
        }
        Map<ResourceLocation, ?> packed = this.minecraft.getResourceManager().listResources(
            "textures/entity/soldier/skins",
            id -> id.getNamespace().equals(StevesArmyMod.MODID) && id.getPath().endsWith(".png"));
        for (ResourceLocation id : packed.keySet()) {
            String path = id.getPath();
            String name = path.substring(path.lastIndexOf('/') + 1, path.length() - 4);
            if (!seen.contains(name.toLowerCase(Locale.ROOT))) {
                entries.add(new SkinEntry(name, name, id));
                seen.add(name.toLowerCase(Locale.ROOT));
            }
        }
        return entries;
    }

    private void apply() {
        if (this.selected != null) {
            NetworkHandler.INSTANCE.sendToServer(new SetSkinPacket(this.soldier.getId(), this.selected.name()));
            onClose();
        }
    }

    private void reset() {
        NetworkHandler.INSTANCE.sendToServer(new SetSkinPacket(this.soldier.getId(), ""));
        onClose();
    }

    private void select(SkinEntry entry) {
        this.selected = entry;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(this.font, this.title, this.width / 2, this.listTop - 16, 0xFFFFFFFF);

        // Preview pane: scaled blit of the selected skin texture.
        int previewX = this.listLeft + PANEL_WIDTH + 10;
        graphics.fill(previewX - 1, this.listTop - 1, previewX + PREVIEW_SIZE + 1, this.listTop + PREVIEW_SIZE + 1, 0xFF555555);
        graphics.fill(previewX, this.listTop, previewX + PREVIEW_SIZE, this.listTop + PREVIEW_SIZE, 0xFF202020);
        if (this.selected != null) {
            graphics.blit(this.selected.texture(), previewX, this.listTop, PREVIEW_SIZE, PREVIEW_SIZE,
                0.0F, 0.0F, 64, 64, 64, 64);
            graphics.drawCenteredString(this.font, this.selected.displayName(),
                previewX + PREVIEW_SIZE / 2, this.listTop + PREVIEW_SIZE + 4, 0xFFFFFF55);
        }
        if (this.soldier.hasYsmModel()) {
            graphics.drawCenteredString(this.font, "YSM model active; skin shows once disabled",
                previewX + PREVIEW_SIZE / 2, this.listTop + PREVIEW_SIZE + 16, 0xFF999999);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private record SkinEntry(String name, String displayName, ResourceLocation texture) {}

    private class SkinList extends ObjectSelectionList<SoldierSkinScreen.SkinRow> {
        SkinList(Minecraft minecraft, int width, int height, int y0, int y1, int itemHeight) {
            super(minecraft, width, height, y0, y1, itemHeight);
            this.setRenderTopAndBottom(false);
            this.setRenderBackground(false);
        }

        @Override
        public int getRowWidth() {
            return this.width - 10;
        }

        @Override
        protected int getScrollbarPosition() {
            return this.x1 - 8;
        }

        @Override
        public int addEntry(SoldierSkinScreen.SkinRow entry) {
            return super.addEntry(entry);
        }
    }

    private class SkinRow extends ObjectSelectionList.Entry<SoldierSkinScreen.SkinRow> {
        private final SkinEntry skin;

        SkinRow(SkinEntry skin) {
            this.skin = skin;
        }

        @Override
        public void render(GuiGraphics graphics, int index, int top, int left, int rowWidth, int rowHeight,
                           int mouseX, int mouseY, boolean hovered, float partialTick) {
            boolean isSelected = SoldierSkinScreen.this.selected == this.skin;
            boolean isCurrent = this.skin.name().equals(SoldierSkinScreen.this.soldier.getSkin());
            int fill = isSelected ? 0x665599FF : hovered ? 0x40FFFFFF : 0x00000000;
            graphics.fill(left - 2, top - 1, left + rowWidth + 2, top + rowHeight + 1, fill);
            String label = this.skin.displayName() + (isCurrent ? " *" : "");
            graphics.drawString(SoldierSkinScreen.this.font, label, left + 3, top + 6,
                isSelected ? 0xFFFFFF55 : 0xFFFFFFFF);
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (button == 0) {
                SoldierSkinScreen.this.select(this.skin);
                return true;
            }
            return false;
        }

        @Override
        public Component getNarration() {
            return Component.literal(this.skin.displayName());
        }
    }
}
