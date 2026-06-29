package com.stevesarmy.client;

import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.network.NetworkHandler;
import com.stevesarmy.network.PingMessage;
import com.stevesarmy.ping.PingType;
import com.stevesarmy.util.RateLimiter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

public class PingWheelHandler {
    private static final RateLimiter rateLimiter = new RateLimiter();
    private static final int HOLD_TIME_MS = 150;
    
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
        
        if (isKeyDown && !wasKeyDown) {
            isWheelActive = true;
            pressStartTime = System.currentTimeMillis();
            
            savedYaw = mc.player.getYRot();
            savedPitch = mc.player.getXRot();
            
            grabMouseForWheel(mc);
            
            StevesArmyMod.LOGGER.info("Ping wheel activated");
        }
        
        if (!isKeyDown && wasKeyDown && isWheelActive) {
            long holdTime = System.currentTimeMillis() - pressStartTime;
            
            releaseMouse(mc);
            
            mc.player.setYRot(savedYaw);
            mc.player.setXRot(savedPitch);
            
            if (holdTime < HOLD_TIME_MS) {
                StevesArmyMod.LOGGER.info("Ping wheel quick tap ({}ms) - no ping sent, vanilla pick block works", holdTime);
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
        
        wasKeyDown = isKeyDown;
    }
    
    private static void grabMouseForWheel(Minecraft mc) {
        long windowHandle = mc.getWindow().getWindow();
        int width = mc.getWindow().getWidth();
        int height = mc.getWindow().getHeight();
        
        GLFW.glfwSetCursorPos(windowHandle, width / 2.0, height / 2.0);
        
        pressMouseX = width / 2.0;
        pressMouseY = height / 2.0;
        
        mc.mouseHandler.releaseMouse();
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
        
        StevesArmyMod.LOGGER.info("Mouse delta: ({}, {}) distance: {}", deltaX, deltaY, distance);
        
        if (distance < 30) {
            StevesArmyMod.LOGGER.info("Center area selected, returning LOCATION");
            return PingType.LOCATION;
        }
        
        double angle = Math.atan2(deltaY, deltaX);
        double degrees = Math.toDegrees(angle);
        
        double adjustedDegrees = degrees + 90;
        if (adjustedDegrees < 0) adjustedDegrees += 360;
        if (adjustedDegrees >= 360) adjustedDegrees -= 360;
        
        int sector = ((int) (adjustedDegrees / 60)) % 6;
        PingType selectedType = PingType.values()[sector];
        
        StevesArmyMod.LOGGER.info("Angle: {} deg, adjusted: {} deg, sector: {}, type: {}", 
            String.format("%.1f", degrees), 
            String.format("%.1f", adjustedDegrees), 
            sector, 
            selectedType);
        
        return selectedType;
    }
    
    private static void sendPing(Minecraft mc, PingType type) {
        LocalPlayer player = mc.player;
        if (player == null) return;
        
        Vec3 eyePos = player.getEyePosition(1.0f);
        Vec3 lookVec = player.getViewVector(1.0f);
        double maxDistance = 64.0;
        Vec3 endPos = eyePos.add(lookVec.scale(maxDistance));
        
        BlockHitResult hitResult = player.level().clip(new ClipContext(
            eyePos,
            endPos,
            ClipContext.Block.OUTLINE,
            ClipContext.Fluid.NONE,
            player
        ));
        
        Vec3 pingPos;
        if (hitResult.getType() != HitResult.Type.MISS) {
            pingPos = hitResult.getLocation();
        } else {
            pingPos = eyePos.add(lookVec.scale(32.0));
        }
        
        int dimension = player.level().dimension().location().hashCode();
        
        StevesArmyMod.LOGGER.info("Ping position: {} type: {}", pingPos, type);
        
        PingMessage message = new PingMessage(type, pingPos, dimension);
        NetworkHandler.INSTANCE.sendToServer(message);
    }
    
    public static boolean isWheelActive() {
        return isWheelActive;
    }
    
    public static double getDeltaX() {
        Minecraft mc = Minecraft.getInstance();
        long windowHandle = mc.getWindow().getWindow();
        double[] xpos = new double[1];
        GLFW.glfwGetCursorPos(windowHandle, xpos, null);
        return xpos[0] - pressMouseX;
    }
    
    public static double getDeltaY() {
        Minecraft mc = Minecraft.getInstance();
        long windowHandle = mc.getWindow().getWindow();
        double[] ypos = new double[1];
        GLFW.glfwGetCursorPos(windowHandle, null, ypos);
        return ypos[0] - pressMouseY;
    }
    
    public static PingType getHoveredType() {
        Minecraft mc = Minecraft.getInstance();
        currentHoveredType = determinePingTypeFromMouse(mc);
        return currentHoveredType;
    }
}