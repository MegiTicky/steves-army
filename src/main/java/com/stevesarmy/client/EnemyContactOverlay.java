package com.stevesarmy.client;

import com.stevesarmy.network.EnemyContactSyncPacket;
import com.stevesarmy.util.MathUtils;
import com.stevesarmy.util.ScreenPos;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

public final class EnemyContactOverlay {
    private static final int BASE_DIAMOND_RADIUS = 5;
    private static final float BASE_DIAMOND_DIAMETER = BASE_DIAMOND_RADIUS * 2.0f;
    private static final int EDGE_MARGIN = 12;
    private static final double DISTANCE_BASELINE_BLOCKS = 96.0;
    private static final double DISTANCE_MAX_SCALE_BLOCKS = 12.0;
    private static final float MAX_DISTANCE_SCALE = 3.0f;
    private static final long LAST_SEEN_FADE_MILLIS = 10_000L;
    private static final long VISIBLE_UPDATE_TIMEOUT_MILLIS = 750L;

    private static final Map<UUID, Contact> contacts = new HashMap<>();

    private EnemyContactOverlay() {
    }

    public static void receive(EnemyContactSyncPacket message) {
        if (message.isRemoved()) {
            contacts.remove(message.getThreatId());
            return;
        }

        long now = System.currentTimeMillis();
        Contact contact = contacts.computeIfAbsent(message.getThreatId(), ignored -> new Contact());
        contact.headPosition = message.getHeadPosition();
        contact.renderHeadPosition = message.getHeadPosition();
        contact.teamColor = message.getTeamColor();
        contact.visible = message.isVisible();
        if (contact.visible) {
            contact.lastVisibleUpdateMillis = now;
            contact.fadeStartMillis = 0L;
        } else if (contact.fadeStartMillis == 0L) {
            contact.fadeStartMillis = now;
        }
    }

    public static void clear() {
        contacts.clear();
    }

    public static void updateScreenPositions(WorldRenderContext context) {
        if (context == null) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return;
        }

        Map<UUID, Entity> renderedEntities = new HashMap<>();
        for (Entity entity : minecraft.level.entitiesForRendering()) {
            if (contacts.containsKey(entity.getUUID())) {
                renderedEntities.put(entity.getUUID(), entity);
            }
        }

        for (Map.Entry<UUID, Contact> entry : contacts.entrySet()) {
            Contact contact = entry.getValue();
            contact.renderHeadPosition = getRenderHeadPosition(contact, renderedEntities.get(entry.getKey()), context.tickDelta);
            contact.headScreenPosition = MathUtils.worldToScreen(contact.renderHeadPosition, context);
            contact.screenPosition = MathUtils.worldToScreen(getMarkerPosition(contact), context);
        }
    }

    public static void render(GuiGraphics graphics, WorldRenderContext context) {
        Minecraft minecraft = Minecraft.getInstance();
        if (context == null || minecraft.player == null || minecraft.level == null) {
            return;
        }

        long now = System.currentTimeMillis();
        int guiWidth = minecraft.getWindow().getGuiScaledWidth();
        int guiHeight = minecraft.getWindow().getGuiScaledHeight();
        Iterator<Contact> iterator = contacts.values().iterator();
        while (iterator.hasNext()) {
            Contact contact = iterator.next();
            updateVisibilityTimeout(contact, now);
            float opacity = getOpacity(contact, now);
            if (opacity <= 0.0f) {
                iterator.remove();
                continue;
            }

            if (contact.screenPosition == null) {
                continue;
            }

            boolean onScreen = isOnScreen(contact.screenPosition, guiWidth, guiHeight);
            float[] screenPosition = resolveScreenPosition(contact, context, guiWidth, guiHeight);
            float diameter = onScreen ? getOnScreenDiamondDiameter(contact, context) : getBaselineDiamondDiameter();
            if (onScreen && isOnScreen(contact.headScreenPosition, guiWidth, guiHeight)) {
                drawConnector(graphics, contact.headScreenPosition, contact.screenPosition, diameter,
                    withAlpha(contact.teamColor, opacity * 0.35f));
            }
            drawDiamond(graphics, screenPosition[0], screenPosition[1], diameter, withAlpha(contact.teamColor, opacity));
        }
    }

    private static void updateVisibilityTimeout(Contact contact, long now) {
        if (contact.visible && now - contact.lastVisibleUpdateMillis > VISIBLE_UPDATE_TIMEOUT_MILLIS) {
            contact.visible = false;
            contact.fadeStartMillis = contact.lastVisibleUpdateMillis + VISIBLE_UPDATE_TIMEOUT_MILLIS;
        }
    }

    private static float getOpacity(Contact contact, long now) {
        if (contact.visible) {
            return 1.0f;
        }
        if (contact.fadeStartMillis == 0L) {
            contact.fadeStartMillis = now;
        }
        return Math.max(0.0f, 1.0f - (now - contact.fadeStartMillis) / (float) LAST_SEEN_FADE_MILLIS);
    }

    private static float[] resolveScreenPosition(Contact contact, WorldRenderContext context, int guiWidth, int guiHeight) {
        ScreenPos projected = contact.screenPosition;
        boolean onScreen = isOnScreen(projected, guiWidth, guiHeight);
        if (onScreen) {
            return new float[] {projected.x, projected.y};
        }

        float centerX = guiWidth / 2.0f;
        float centerY = guiHeight / 2.0f;
        float directionX;
        float directionY;
        if (projected.isBehindCamera()) {
            Vec3 toContact = getMarkerPosition(contact).subtract(context.camera.getPosition());
            var left = context.camera.getLeftVector();
            var up = context.camera.getUpVector();
            directionX = (float) -(left.x * toContact.x + left.y * toContact.y + left.z * toContact.z);
            directionY = (float) -(up.x * toContact.x + up.y * toContact.y + up.z * toContact.z);
        } else {
            directionX = projected.x - centerX;
            directionY = projected.y - centerY;
        }
        if (Math.abs(directionX) < 0.001f && Math.abs(directionY) < 0.001f) {
            directionY = -1.0f;
        }

        float edgeMargin = Math.max(EDGE_MARGIN, getBaselineDiamondDiameter() / 2.0f + 4.0f);
        float availableX = Math.max(1.0f, centerX - edgeMargin);
        float availableY = Math.max(1.0f, centerY - edgeMargin);
        float scale = Math.min(availableX / Math.abs(directionX), availableY / Math.abs(directionY));
        return new float[] {
            Math.round(centerX + directionX * scale),
            Math.round(centerY + directionY * scale)
        };
    }

    private static boolean isOnScreen(ScreenPos projected, int guiWidth, int guiHeight) {
        return !projected.isBehindCamera()
            && projected.x >= 0.0f && projected.x <= guiWidth
            && projected.y >= 0.0f && projected.y <= guiHeight;
    }

    private static void drawDiamond(GuiGraphics graphics, float centerX, float centerY, float diameter, int color) {
        float scale = diameter / BASE_DIAMOND_DIAMETER;
        graphics.pose().pushPose();
        graphics.pose().translate(centerX, centerY, 0.0f);
        graphics.pose().scale(scale, scale, 1.0f);
        for (int y = -BASE_DIAMOND_RADIUS; y <= BASE_DIAMOND_RADIUS; y++) {
            int halfWidth = BASE_DIAMOND_RADIUS - Math.abs(y);
            graphics.fill(-halfWidth, y, halfWidth + 1, y + 1, color);
        }
        graphics.pose().popPose();
    }

    private static void drawConnector(GuiGraphics graphics, ScreenPos head, ScreenPos marker, float diameter, int color) {
        float diamondScale = diameter / BASE_DIAMOND_DIAMETER;
        float centeredX = (head.x + marker.x) * 0.5f + 0.5f * diamondScale;
        int x = Math.round(centeredX);
        int startY = Math.min(Math.round(head.y), Math.round(marker.y));
        int endY = Math.max(Math.round(head.y), Math.round(marker.y));
        if (endY > startY) {
            graphics.fill(x, startY, x + 1, endY + 1, color);
        }
    }

    private static Vec3 getMarkerPosition(Contact contact) {
        return contact.renderHeadPosition.add(0.0, StevesArmyClientConfig.ENEMY_CONTACT_PING_HEIGHT_OFFSET.get(), 0.0);
    }

    private static Vec3 getRenderHeadPosition(Contact contact, Entity entity, float partialTick) {
        if (contact.visible && entity instanceof LivingEntity living && living.isAlive()) {
            return living.getPosition(partialTick).add(0.0, living.getBbHeight(), 0.0);
        }
        return contact.headPosition;
    }

    private static float getOnScreenDiamondDiameter(Contact contact, WorldRenderContext context) {
        double distance = context.camera.getPosition().distanceTo(getMarkerPosition(contact));
        double clampedDistance = Math.max(DISTANCE_MAX_SCALE_BLOCKS, Math.min(DISTANCE_BASELINE_BLOCKS, distance));
        float progress = (float) (Math.log(DISTANCE_BASELINE_BLOCKS / clampedDistance)
            / Math.log(DISTANCE_BASELINE_BLOCKS / DISTANCE_MAX_SCALE_BLOCKS));
        return getBaselineDiamondDiameter() * (1.0f + (MAX_DISTANCE_SCALE - 1.0f) * progress);
    }

    private static float getBaselineDiamondDiameter() {
        return StevesArmyClientConfig.ENEMY_CONTACT_PING_BASELINE_SIZE.get().floatValue();
    }

    private static int withAlpha(int color, float opacity) {
        return ((int) (opacity * 255.0f) << 24) | (color & 0x00FFFFFF);
    }

    private static final class Contact {
        private Vec3 headPosition = Vec3.ZERO;
        private Vec3 renderHeadPosition = Vec3.ZERO;
        private ScreenPos headScreenPosition;
        private ScreenPos screenPosition;
        private int teamColor;
        private boolean visible;
        private long lastVisibleUpdateMillis;
        private long fadeStartMillis;
    }
}
