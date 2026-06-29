package com.stevesarmy.client;

import net.minecraft.client.Camera;
import org.joml.Matrix4f;

public class WorldRenderContext {
    public final Matrix4f modelViewMatrix;
    public final Matrix4f projectionMatrix;
    public final float tickDelta;
    public final Camera camera;
    
    public WorldRenderContext(Matrix4f modelViewMatrix, Matrix4f projectionMatrix, float tickDelta, Camera camera) {
        this.modelViewMatrix = modelViewMatrix;
        this.projectionMatrix = projectionMatrix;
        this.tickDelta = tickDelta;
        this.camera = camera;
    }
}
