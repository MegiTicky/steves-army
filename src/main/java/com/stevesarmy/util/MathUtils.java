package com.stevesarmy.util;

import com.stevesarmy.client.WorldRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector4f;

public class MathUtils {
    private MathUtils() {}
    
    public static ScreenPos worldToScreen(Vec3 worldPos, WorldRenderContext ctx) {
        Minecraft mc = Minecraft.getInstance();
        var window = mc.getWindow();
        
        var cameraPos = ctx.camera.getPosition();
        var worldPosRel = new Vector4f(
            (float)(worldPos.x - cameraPos.x),
            (float)(worldPos.y - cameraPos.y),
            (float)(worldPos.z - cameraPos.z),
            1.0f
        );
        
        worldPosRel.mul(ctx.modelViewMatrix);
        worldPosRel.mul(ctx.projectionMatrix);
        
        float depth = worldPosRel.w;
        
        if (depth != 0) {
            worldPosRel.div(depth);
        }
        
        return new ScreenPos(
            window.getGuiScaledWidth() * (0.5f + worldPosRel.x * 0.5f),
            window.getGuiScaledHeight() * (0.5f - worldPosRel.y * 0.5f),
            depth
        );
    }
}