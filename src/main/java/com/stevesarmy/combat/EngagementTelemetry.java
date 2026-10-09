package com.stevesarmy.combat;

/**
 * Per-soldier engagement state written by the combat goal's fire pipeline and
 * read by the cover goal's state snapshot, the debug card and the sim
 * detectors. Lives on the entity (lazily created — entity goal registration
 * runs inside the superclass constructor, so an eager field would not exist
 * yet when {@code registerGoals} builds the goals) and survives goal swaps so
 * a snapshot taken by {@code CoverTacticalGoal} sees what the combat goal
 * last decided.
 *
 * <p>Movement-permission fields ({@code movingSupported}/{@code
 * movementBlockReason}) are written by the contact plan (INC 2 WP2) and read
 * by the same telemetry consumers; until the plan is active they default to
 * "supported".
 */
public class EngagementTelemetry {
    private EngagementGateReason gateReason = EngagementGateReason.NONE;
    /** Tick the current gate reason was first recorded; -1 before first write. */
    private long gateSinceTick = -1;
    /** Last tick a direct-fire shot actually left the muzzle; MIN_VALUE = never. */
    private long lastShotTick = Long.MIN_VALUE;

    private boolean targetVisible;
    private boolean weaponReady;

    /** Body-yaw error vs the known threat in degrees; -1 when no threat known. */
    private float facingErrorDeg = -1.0f;

    private boolean movingSupported = true;
    private String movementBlockReason = "";
    /** Who owns/denied the current movement decision (contact plan role, gate name). */
    private String movementOwner = "";

    public void noteGate(EngagementGateReason reason, long tick) {
        if (reason != this.gateReason) {
            this.gateReason = reason;
            this.gateSinceTick = tick;
        }
    }

    public void noteShot(long tick) {
        this.lastShotTick = tick;
    }

    public EngagementGateReason getGateReason() {
        return gateReason;
    }

    public long getGateSinceTick() {
        return gateSinceTick;
    }

    public long getLastShotTick() {
        return lastShotTick;
    }

    public int getTicksInGate(long nowTick) {
        return gateSinceTick < 0 ? 0 : (int) Math.max(0L, nowTick - gateSinceTick);
    }

    /** Ticks since the last successful shot; -1 when none was ever fired. */
    public int getTicksSinceLastShot(long nowTick) {
        return lastShotTick == Long.MIN_VALUE ? -1 : (int) Math.max(0L, nowTick - lastShotTick);
    }

    public void setTargetVisible(boolean targetVisible) {
        this.targetVisible = targetVisible;
    }

    public boolean isTargetVisible() {
        return targetVisible;
    }

    public void setWeaponReady(boolean weaponReady) {
        this.weaponReady = weaponReady;
    }

    public boolean isWeaponReady() {
        return weaponReady;
    }

    public void setFacingErrorDeg(float facingErrorDeg) {
        this.facingErrorDeg = facingErrorDeg;
    }

    public float getFacingErrorDeg() {
        return facingErrorDeg;
    }

    public void setMovingSupported(boolean movingSupported, String blockReason, String owner) {
        this.movingSupported = movingSupported;
        this.movementBlockReason = movingSupported ? "" : blockReason;
        this.movementOwner = owner == null ? "" : owner;
    }

    public boolean isMovingSupported() {
        return movingSupported;
    }

    public String getMovementBlockReason() {
        return movementBlockReason;
    }

    public String getMovementOwner() {
        return movementOwner;
    }

    /** Short debug-card segment, e.g. {@code AIM_QUALITY 37t shot12tago}. */
    public String describe(long nowTick) {
        if (gateReason == EngagementGateReason.NONE && lastShotTick == Long.MIN_VALUE) {
            return "";
        }
        StringBuilder sb = new StringBuilder(gateReason.wire());
        if (gateReason != EngagementGateReason.NONE) {
            sb.append(' ').append(getTicksInGate(nowTick)).append('t');
        }
        int sinceShot = getTicksSinceLastShot(nowTick);
        if (sinceShot >= 0) {
            sb.append(" shot").append(sinceShot).append('t');
        }
        if (facingErrorDeg >= 0.0f) {
            sb.append(String.format(" fe%.0f", facingErrorDeg));
        }
        if (!movingSupported) {
            sb.append(" move:").append(movementBlockReason.isEmpty() ? "blocked" : movementBlockReason);
        }
        return sb.toString();
    }
}
