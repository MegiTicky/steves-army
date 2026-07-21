package com.stevesarmy.ping;

import com.stevesarmy.util.ScreenPos;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

public class Ping {
    private final UUID pingId;
    private final UUID authorId;
    private final String authorName;
    private final PingType type;
    private final Vec3 position;
    private final int dimension;
    private final int teamColor;
    private final long timestamp;
    private final String scopeLabel;
    
    private ScreenPos screenPos;
    private double distance;
    private float scale;
    
    public Ping(UUID authorId, String authorName, PingType type, Vec3 position, int dimension, int teamColor) {
        this(authorId, authorName, type, position, dimension, teamColor, "");
    }

    public Ping(UUID authorId, String authorName, PingType type, Vec3 position, int dimension, int teamColor, String scopeLabel) {
        this.pingId = UUID.randomUUID();
        this.authorId = authorId;
        this.authorName = authorName;
        this.type = type;
        this.position = position;
        this.dimension = dimension;
        this.teamColor = teamColor;
        this.timestamp = System.currentTimeMillis();
        this.scopeLabel = scopeLabel;
    }
    
    public UUID getPingId() { return pingId; }
    public UUID getAuthorId() { return authorId; }
    public String getAuthorName() { return authorName; }
    public PingType getType() { return type; }
    public Vec3 getPosition() { return position; }
    public int getDimension() { return dimension; }
    public int getTeamColor() { return teamColor; }
    public long getTimestamp() { return timestamp; }
    public String getScopeLabel() { return scopeLabel; }
    
    public ScreenPos getScreenPos() { return screenPos; }
    public double getDistance() { return distance; }
    public float getScale() { return scale; }
    
    public void updateScreenPosition(ScreenPos screenPos, double distance, float scale) {
        this.screenPos = screenPos;
        this.distance = distance;
        this.scale = scale;
    }
    
    public boolean isExpired(long durationMs) {
        return System.currentTimeMillis() - timestamp > durationMs;
    }
}
