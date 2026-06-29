package com.stevesarmy.util;

public class ScreenPos {
    public final float x;
    public final float y;
    public final float depth;
    
    public ScreenPos(float x, float y, float depth) {
        this.x = x;
        this.y = y;
        this.depth = depth;
    }
    
    public boolean isBehindCamera() {
        return depth <= 0f;
    }
}
