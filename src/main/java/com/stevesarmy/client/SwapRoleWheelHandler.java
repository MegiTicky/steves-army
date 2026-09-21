package com.stevesarmy.client;

import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.entity.SoldierRole;
import com.stevesarmy.network.NetworkHandler;
import com.stevesarmy.network.SquadStatusSyncPacket;
import com.stevesarmy.network.SwapSoldierPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Swap input: tap = swap with the soldier under the crosshair (or swap back to
 * the previous body when the crosshair is empty); hold ≥ threshold = role wheel,
 * with one sector per role present in the squad plus a swap-back sector.
 */
public class SwapRoleWheelHandler {
    private static final int HOLD_THRESHOLD_MS = 200;
    private static final double CENTER_DEAD_ZONE = 30.0;

    private static boolean wasKeyDown = false;
    private static boolean wheelActive = false;
    private static boolean pressConsumed = false;
    private static long pressStartTime = 0;
    private static double pressMouseX = 0;
    private static double pressMouseY = 0;
    private static SoldierEntity pressTarget = null;

    private static List<WheelOption> options = List.of();
    private static WheelOption hovered = null;

    /** One wheel sector: either a squad role or the swap-back option. */
    public static class WheelOption {
        public final SoldierRole role;
        public final boolean previousBody;
        public final String label;
        /** Optional second line under {@link #label} (the previous body's name). */
        public final String subLabel;
        public final int count;

        private WheelOption(SoldierRole role, boolean previousBody, String label, String subLabel, int count) {
            this.role = role;
            this.previousBody = previousBody;
            this.label = label;
            this.subLabel = subLabel;
            this.count = count;
        }

        static WheelOption ofRole(SoldierRole role, int count) {
            return new WheelOption(role, false, role.getDisplayName().getString(), null, count);
        }

        static WheelOption previous(String label, String subLabel) {
            return new WheelOption(null, true, label, subLabel, 1);
        }
    }

    public static void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            if (wheelActive) releaseMouse(mc);
            wheelActive = false;
            wasKeyDown = false;
            pressTarget = null;
            return;
        }

        boolean isKeyDown = KeyBindings.SWAP_SOLDIER.isDown();

        if (isKeyDown && !wasKeyDown) {
            pressStartTime = System.currentTimeMillis();
            pressTarget = findLookedAtSoldier(mc);
            pressConsumed = false;
            wheelActive = false;
        }

        if (isKeyDown && wasKeyDown && !wheelActive && !pressConsumed
            && System.currentTimeMillis() - pressStartTime >= HOLD_THRESHOLD_MS) {
            openWheel(mc);
        }

        if (!isKeyDown && wasKeyDown) {
            if (wheelActive) {
                releaseMouse(mc);
                WheelOption selected = hovered;
                hovered = null;
                options = List.of();
                wheelActive = false;
                if (selected != null) {
                    sendToServer(selected);
                }
            } else if (!pressConsumed) {
                handleTap(mc);
            }
            pressTarget = null;
        }

        wasKeyDown = isKeyDown;
    }

    private static void handleTap(Minecraft mc) {
        if (pressTarget != null && pressTarget.isAlive()) {
            NetworkHandler.INSTANCE.sendToServer(SwapSoldierPacket.byEntity(pressTarget.getUUID()));
            return;
        }

        UUID lastSwapped = ClientSquadData.INSTANCE.getLastSwappedSoldierId();
        SquadStatusSyncPacket.SoldierStatusEntry entry =
            lastSwapped != null ? ClientSquadData.INSTANCE.getEntry(lastSwapped) : null;
        if (entry != null && entry.loaded) {
            NetworkHandler.INSTANCE.sendToServer(SwapSoldierPacket.byLast());
        } else {
            mc.player.displayClientMessage(
                Component.literal("Look at a squadmate to swap, or hold V for the role wheel."), true);
        }
    }

    private static void openWheel(Minecraft mc) {
        options = buildOptions();
        if (options.isEmpty()) {
            pressConsumed = true;
            mc.player.displayClientMessage(
                Component.literal("No loaded squad soldiers to swap with."), true);
            return;
        }
        wheelActive = true;
        grabMouseForWheel(mc);
        StevesArmyMod.LOGGER.debug("Swap role wheel opened with {} sectors", options.size());
    }

    private static List<WheelOption> buildOptions() {
        Map<SoldierRole, Integer> roleCounts = new LinkedHashMap<>();
        for (SquadStatusSyncPacket.SoldierStatusEntry entry : ClientSquadData.INSTANCE.getAllEntries()) {
            if (!entry.loaded) continue;
            roleCounts.merge(entry.getRole(), 1, Integer::sum);
        }

        List<WheelOption> built = new ArrayList<>();
        UUID lastSwapped = ClientSquadData.INSTANCE.getLastSwappedSoldierId();
        SquadStatusSyncPacket.SoldierStatusEntry lastEntry =
            lastSwapped != null ? ClientSquadData.INSTANCE.getEntry(lastSwapped) : null;
        if (lastEntry != null && lastEntry.loaded) {
            String name = lastEntry.name;
            if (name.length() > 14) {
                name = name.substring(0, 13) + "…";
            }
            built.add(WheelOption.previous(
                Component.translatable("gui.steves_army.swap_wheel.previous").getString(), name));
        }
        for (Map.Entry<SoldierRole, Integer> roleEntry : roleCounts.entrySet()) {
            built.add(WheelOption.ofRole(roleEntry.getKey(), roleEntry.getValue()));
        }
        return built;
    }

    private static void sendToServer(WheelOption option) {
        if (option.previousBody) {
            NetworkHandler.INSTANCE.sendToServer(SwapSoldierPacket.byLast());
        } else {
            NetworkHandler.INSTANCE.sendToServer(SwapSoldierPacket.byRole(option.role));
        }
    }

    /**
     * True crosshair pick: the view ray must pass through the soldier's
     * (slightly inflated) hitbox, blocked by walls in between — the earlier
     * ±10° cone sweep let a soldier far off-center trigger the swap when the
     * player was looking into the air. Nearest soldier along the ray wins.
     */
    private static final double LOOK_RANGE = 32.0;
    private static final double LOOK_HITBOX_INFLATION = 0.3;

    private static SoldierEntity findLookedAtSoldier(Minecraft mc) {
        if (mc.player == null || mc.level == null) {
            return null;
        }
        Vec3 eyePos = mc.player.getEyePosition();
        Vec3 endPos = eyePos.add(mc.player.getLookAngle().scale(LOOK_RANGE));

        BlockHitResult blockHit = mc.level.clip(new ClipContext(
            eyePos, endPos, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player));
        double maxDist = blockHit.getType() == HitResult.Type.BLOCK
            ? Math.min(LOOK_RANGE, blockHit.getLocation().distanceTo(eyePos))
            : LOOK_RANGE;

        double bestDist = Double.MAX_VALUE;
        SoldierEntity best = null;
        for (SoldierEntity soldier : mc.level.getEntitiesOfClass(SoldierEntity.class,
                mc.player.getBoundingBox().inflate(LOOK_RANGE))) {
            if (!soldier.isAlive() || !soldier.isOwnedBy(mc.player)) {
                continue;
            }
            if (ClientSquadData.INSTANCE.getEntry(soldier.getUUID()) == null) {
                continue;
            }
            double dist = rayHitDistance(eyePos, endPos, soldier);
            if (dist < 0 || dist > maxDist) {
                continue;
            }
            if (dist < bestDist) {
                bestDist = dist;
                best = soldier;
            }
        }
        return best;
    }

    /** Distance along the view ray where it enters the soldier's hitbox, or -1. */
    private static double rayHitDistance(Vec3 from, Vec3 to, SoldierEntity soldier) {
        return soldier.getBoundingBox().inflate(LOOK_HITBOX_INFLATION).clip(from, to)
            .map(from::distanceTo)
            .orElse(-1.0);
    }

    private static void grabMouseForWheel(Minecraft mc) {
        long windowHandle = mc.getWindow().getWindow();
        double centerX = mc.getWindow().getWidth() / 2.0;
        double centerY = mc.getWindow().getHeight() / 2.0;
        GLFW.glfwSetCursorPos(windowHandle, centerX, centerY);
        pressMouseX = centerX;
        pressMouseY = centerY;
        mc.mouseHandler.releaseMouse();
    }

    private static void releaseMouse(Minecraft mc) {
        mc.mouseHandler.grabMouse();
    }

    /** Called by the renderer every frame; caches the hover for the release snapshot. */
    public static WheelOption getHoveredOption() {
        if (!wheelActive || options.isEmpty()) {
            hovered = null;
            return null;
        }
        long windowHandle = Minecraft.getInstance().getWindow().getWindow();
        double[] xpos = new double[1];
        double[] ypos = new double[1];
        GLFW.glfwGetCursorPos(windowHandle, xpos, ypos);

        double deltaX = xpos[0] - pressMouseX;
        double deltaY = ypos[0] - pressMouseY;
        if (Math.sqrt(deltaX * deltaX + deltaY * deltaY) < CENTER_DEAD_ZONE) {
            hovered = null;
            return null;
        }

        double angle = Math.toDegrees(Math.atan2(deltaY, deltaX)) + 90;
        if (angle < 0) angle += 360;
        if (angle >= 360) angle -= 360;

        double sectorSize = 360.0 / options.size();
        int index = ((int) (angle / sectorSize)) % options.size();
        hovered = options.get(index);
        return hovered;
    }

    public static boolean isWheelActive() {
        return wheelActive;
    }

    public static List<WheelOption> getOptions() {
        return options;
    }
}
