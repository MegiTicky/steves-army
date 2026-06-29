package com.stevesarmy.ping;

import com.stevesarmy.client.WorldRenderContext;
import com.stevesarmy.util.MathUtils;
import com.stevesarmy.util.ScreenPos;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class PingManager {
    private static final long PING_DURATION_MS = 7000;
    
    private static final List<Ping> activePings = new ArrayList<>();
    
    public static void clearPings() {
        activePings.clear();
    }
    
    public static void addPing(Ping ping) {
        activePings.add(ping);
    }
    
    public static List<Ping> getActivePings() {
        return new ArrayList<>(activePings);
    }
    
    public static void tick() {
        Iterator<Ping> iter = activePings.iterator();
        while (iter.hasNext()) {
            Ping ping = iter.next();
            if (ping.isExpired(PING_DURATION_MS)) {
                iter.remove();
            }
        }
    }
    
    public static void updatePingScreenPositions(WorldRenderContext ctx) {
        if (ctx == null) return;
        
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        
        int currentDimension = mc.level.dimension().location().hashCode();
        Vec3 cameraPos = ctx.camera.getPosition();
        
        for (Ping ping : activePings) {
            if (ping.getDimension() != currentDimension) {
                ping.updateScreenPosition(null, Double.MAX_VALUE, 0f);
                continue;
            }
            
            Vec3 pingPos = ping.getPosition();
            double distance = cameraPos.distanceTo(pingPos);
            
            if (distance < 1.0 || distance > 256.0) {
                ping.updateScreenPosition(null, distance, 0f);
                continue;
            }
            
            ScreenPos screenPos = MathUtils.worldToScreen(pingPos, ctx);
            
            float scale = calculateScale(distance);
            
            ping.updateScreenPosition(screenPos, distance, scale);
        }
    }
    
    private static float calculateScale(double distance) {
        double scale = 2.0 / Math.pow(distance, 0.3);
        return (float) Math.max(0.5, Math.min(2.0, scale));
    }
    
    public static boolean isEmpty() {
        return activePings.isEmpty();
    }
}