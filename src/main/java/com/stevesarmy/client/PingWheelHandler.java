package com.stevesarmy.client;

import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.network.NetworkHandler;
import com.stevesarmy.network.PingMessage;
import com.stevesarmy.ping.PingType;
import com.stevesarmy.transport.TransportOrder;
import com.stevesarmy.util.RateLimiter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.AbstractGlassBlock;
import net.minecraft.world.level.block.StainedGlassPaneBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

public class PingWheelHandler {
    public record MouseDelta(double x, double y) {}

    private static final RateLimiter rateLimiter = new RateLimiter();
    private static final int HOLD_TIME_MS = 150;

    private static boolean fireTeamShortcutPress;

    static boolean takeFireTeamShortcutPress() {
        boolean value = fireTeamShortcutPress;
        fireTeamShortcutPress = false;
        return value;
    }

    private static boolean isWheelActive = false;
    private static boolean wasKeyDown = false;
    private static long pressStartTime = 0;
    private static double pressMouseX = 0;
    private static double pressMouseY = 0;

    private static float savedYaw = 0;
    private static float savedPitch = 0;
    private static PingType currentHoveredType = PingType.LOCATION;

    public static void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            if (isWheelActive) {
                releaseMouse(mc);
            }
            isWheelActive = false;
            wasKeyDown = false;
            return;
        }

        boolean isKeyDown = KeyBindings.isPingWheelKeyDown();
        boolean openingPress = WheelInteractionController.takePingPress() | (isKeyDown && !wasKeyDown);

        // Skip if Ctrl is held and fire team wheel is enabled (it takes priority)
        long window = mc.getWindow().getWindow();
        boolean ctrlHeld = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_LEFT_CONTROL) == GLFW.GLFW_PRESS
            || GLFW.glfwGetKey(window, GLFW.GLFW_KEY_RIGHT_CONTROL) == GLFW.GLFW_PRESS;
        if (StevesArmyClientConfig.ENABLE_FIRE_TEAM_WHEEL.get() && ctrlHeld && (isKeyDown || openingPress)) {
            fireTeamShortcutPress = openingPress;
            cancel();
            wasKeyDown = isKeyDown;
            return;
        }

        if (!WheelInteractionController.canOpen()) {
            cancel();
            wasKeyDown = isKeyDown;
            return;
        }

        if (openingPress && isWheelActive && WheelInteractionController.isClickMode()) {
            cancel();
            wasKeyDown = isKeyDown;
            return;
        }

        if (openingPress) {
            WheelInteractionController.beforeOpen();
            isWheelActive = true;
            pressStartTime = System.currentTimeMillis();

            savedYaw = mc.player.getYRot();
            savedPitch = mc.player.getXRot();

            grabMouseForWheel(mc);

            WheelCycleController.onSessionActivated();

            StevesArmyMod.LOGGER.info("Ping wheel activated");
        }

        if (!isKeyDown && wasKeyDown && isWheelActive && !WheelInteractionController.isClickMode()) {
            finish(false);
        }

        wasKeyDown = isKeyDown;
    }

    public static void confirm() {
        if (isWheelActive) finish(true);
    }

    public static void cancel() {
        if (!isWheelActive) return;
        isWheelActive = false;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && mc.screen == null && mc.isWindowActive()) releaseMouse(mc);
    }

    private static void finish(boolean force) {
        Minecraft mc = Minecraft.getInstance();

        long holdTime = System.currentTimeMillis() - pressStartTime;

        // grabMouse() below recenters the cursor, so the vehicle-page hover must be
        // captured before the mouse is re-grabbed; the ping path uses the type that
        // the renderer cached while the wheel was open.
        if (force) currentHoveredType = getHoveredType();
        TransportOrder vehicleAction = WheelCycleController.isVehiclePage()
            ? VehicleWheelHandler.getHoveredAction() : null;

        releaseMouse(mc);

        mc.player.setYRot(savedYaw);
        mc.player.setXRot(savedPitch);

        if (!force && holdTime < HOLD_TIME_MS) {
            StevesArmyMod.LOGGER.info("Ping wheel quick tap ({}ms) - no ping sent, vanilla pick block works", holdTime);
        } else if (WheelCycleController.isVehiclePage()) {
            VehicleWheelHandler.fireSelected(mc, vehicleAction);
        } else {
            PingType selectedType = currentHoveredType;
            StevesArmyMod.LOGGER.info("Ping wheel released after {}ms, selected type: {}", holdTime, selectedType);

            if (!rateLimiter.checkExceeded()) {
                sendPing(mc, selectedType);
                StevesArmyMod.LOGGER.info("Ping sent: {} at {}", selectedType, mc.player.blockPosition());
            } else {
                StevesArmyMod.LOGGER.warn("Ping rate limited");
            }
        }

        isWheelActive = false;
        PingWheelRenderer.resetLogFlag();
    }

    private static void grabMouseForWheel(Minecraft mc) {
        long windowHandle = mc.getWindow().getWindow();
        // GLFW cursor positions use logical window units, not Retina framebuffer pixels.
        mc.mouseHandler.releaseMouse();
        int[] width = new int[1];
        int[] height = new int[1];
        GLFW.glfwGetWindowSize(windowHandle, width, height);
        GLFW.glfwSetCursorPos(windowHandle, width[0] / 2.0, height[0] / 2.0);
        double[] cursorX = new double[1];
        double[] cursorY = new double[1];
        GLFW.glfwGetCursorPos(windowHandle, cursorX, cursorY);
        pressMouseX = cursorX[0];
        pressMouseY = cursorY[0];
    }

    private static void releaseMouse(Minecraft mc) {
        mc.mouseHandler.grabMouse();
    }

    private static PingType determinePingTypeFromMouse(Minecraft mc) {
        long windowHandle = mc.getWindow().getWindow();
        double[] xpos = new double[1];
        double[] ypos = new double[1];
        GLFW.glfwGetCursorPos(windowHandle, xpos, ypos);

        double currentX = xpos[0];
        double currentY = ypos[0];

        double deltaX = currentX - pressMouseX;
        double deltaY = currentY - pressMouseY;

        double distance = Math.sqrt(deltaX * deltaX + deltaY * deltaY);

        if (distance < 30) {
            return PingType.LOCATION;
        }

        double angle = Math.atan2(deltaY, deltaX);
        double degrees = Math.toDegrees(angle);

        double adjustedDegrees = degrees + 90;
        if (adjustedDegrees < 0) adjustedDegrees += 360;
        if (adjustedDegrees >= 360) adjustedDegrees -= 360;

        int numTypes = PingType.values().length;
        double sectorSize = 360.0 / numTypes;
        int sector = ((int) (adjustedDegrees / sectorSize)) % numTypes;
        PingType selectedType = PingType.values()[sector];

        return selectedType;
    }

    private static void sendPing(Minecraft mc, PingType type) {
        LocalPlayer player = mc.player;
        if (player == null) return;

        Vec3 pingPos = findCrosshairPosition(mc);

        int dimension = player.level().dimension().location().hashCode();

        StevesArmyMod.LOGGER.info("Ping position: {} type: {}", pingPos, type);

        PingMessage message = new PingMessage(type, pingPos, dimension, FireTeamScopeState.INSTANCE.getCurrentScope());
        NetworkHandler.INSTANCE.sendToServer(message);
    }

    /** Crosshair world position, continuing through glass and non-colliding vegetation. */
    static Vec3 findCrosshairPosition(Minecraft mc) {
        LocalPlayer player = mc.player;
        if (player == null) return null;

        int renderDistanceChunks = mc.options.renderDistance().get();
        double maxDistance = renderDistanceChunks * 16.0;

        Vec3 eyePos = player.getEyePosition(1.0f);
        Vec3 lookVec = player.getViewVector(1.0f);
        Vec3 endPos = eyePos.add(lookVec.scale(maxDistance));

        BlockHitResult hitResult = clipPingTarget(player, eyePos, endPos, lookVec);
        if (hitResult.getType() != HitResult.Type.MISS) {
            return hitResult.getLocation();
        }
        return eyePos.add(lookVec.scale(maxDistance));
    }

    /** The entity under the crosshair, limited by solid blocks along the same ping ray. */
    static Entity findCrosshairEntity(Minecraft mc) {
        LocalPlayer player = mc.player;
        if (player == null) return null;

        double maxDistance = mc.options.renderDistance().get() * 16.0;
        Vec3 eyePos = player.getEyePosition(1.0f);
        Vec3 lookVec = player.getViewVector(1.0f);
        Vec3 endPos = eyePos.add(lookVec.scale(maxDistance));
        BlockHitResult blockHit = clipPingTarget(player, eyePos, endPos, lookVec);
        Vec3 entityRayEnd = blockHit.getType() == HitResult.Type.MISS
            ? endPos : blockHit.getLocation();
        double entityRayDistanceSqr = eyePos.distanceToSqr(entityRayEnd);
        if (entityRayDistanceSqr < 1.0e-6) return null;

        AABB searchBox = player.getBoundingBox()
            .expandTowards(entityRayEnd.subtract(eyePos))
            .inflate(1.0);
        EntityHitResult entityHit = ProjectileUtil.getEntityHitResult(
            player, eyePos, entityRayEnd, searchBox,
            entity -> !entity.isSpectator() && entity.isPickable(), entityRayDistanceSqr);
        return entityHit != null ? entityHit.getEntity() : null;
    }

    /**
     * Continues through glass and non-colliding vegetation, but retains thin
     * collidable blocks such as fences and rails as valid ping targets.
     */
    private static BlockHitResult clipPingTarget(LocalPlayer player, Vec3 start, Vec3 end, Vec3 direction) {
        Vec3 rayStart = start;
        for (int ignoredBlocks = 0; ignoredBlocks < 64; ignoredBlocks++) {
            BlockHitResult hit = player.level().clip(new ClipContext(
                rayStart,
                end,
                ClipContext.Block.OUTLINE,
                ClipContext.Fluid.NONE,
                player
            ));
            if (hit.getType() == HitResult.Type.MISS || !isPingTransparent(player, hit)) {
                return hit;
            }

            // Move beyond the hit point so the next clip cannot hit this block again.
            rayStart = hit.getLocation().add(direction.scale(0.001));
        }

        return BlockHitResult.miss(end, net.minecraft.core.Direction.getNearest(direction.x, direction.y, direction.z),
            net.minecraft.core.BlockPos.containing(end));
    }

    private static boolean isPingTransparent(LocalPlayer player, BlockHitResult hit) {
        BlockState state = player.level().getBlockState(hit.getBlockPos());
        return state.getBlock() instanceof AbstractGlassBlock
            || state.getBlock() instanceof StainedGlassPaneBlock
            || state.getCollisionShape(player.level(), hit.getBlockPos()).isEmpty();
    }

    public static boolean isWheelActive() {
        return isWheelActive;
    }

    /** Reads both cursor coordinates together for compatibility with virtual mouse backends. */
    public static MouseDelta getMouseDelta() {
        Minecraft mc = Minecraft.getInstance();
        long windowHandle = mc.getWindow().getWindow();
        double[] xpos = new double[1];
        double[] ypos = new double[1];
        GLFW.glfwGetCursorPos(windowHandle, xpos, ypos);
        return new MouseDelta(xpos[0] - pressMouseX, ypos[0] - pressMouseY);
    }

    public static PingType getHoveredType() {
        Minecraft mc = Minecraft.getInstance();
        currentHoveredType = determinePingTypeFromMouse(mc);
        return currentHoveredType;
    }
}
