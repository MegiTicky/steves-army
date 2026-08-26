package com.stevesarmy.combat;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side VPB gun state for an AI soldier. Vic's Point Blank keeps its own
 * gun state machine in GunClientState, which is client-only and player-bound,
 * so Steve's Army tracks the reload/draw/aim/cooldown timers an AI needs here.
 */
public final class VpbEntityState {
    private static final Map<UUID, VpbEntityState> STATES = new ConcurrentHashMap<>();

    private int reloadEndTick = -1;
    private int drawEndTick = -1;
    private long nextShotTimeMs = -1;
    private int aimStartTick = -1;
    private boolean aiming = false;

    private VpbEntityState() {}

    public static VpbEntityState get(UUID uuid) {
        return STATES.computeIfAbsent(uuid, u -> new VpbEntityState());
    }

    public static void remove(UUID uuid) {
        STATES.remove(uuid);
    }

    public boolean isReloading(int currentTick) {
        return currentTick < reloadEndTick;
    }

    public void setReloading(int currentTick, int durationTicks) {
        reloadEndTick = durationTicks > 0 ? currentTick + durationTicks : -1;
    }

    public void cancelReload() {
        reloadEndTick = -1;
    }

    public boolean isDrawing(int currentTick) {
        return currentTick < drawEndTick;
    }

    public void setDrawing(int currentTick, int durationTicks) {
        drawEndTick = durationTicks > 0 ? currentTick + durationTicks : -1;
    }

    public long getRemainingShootCooldownMs(long nowMs) {
        if (nextShotTimeMs < 0) return 0;
        return Math.max(0, nextShotTimeMs - nowMs);
    }

    public void setShootCooldown(long nowMs, long cooldownMs) {
        nextShotTimeMs = cooldownMs > 0 ? nowMs + cooldownMs : -1;
    }

    public void setAiming(boolean isAiming, int currentTick) {
        this.aiming = isAiming;
        if (isAiming && aimStartTick < 0) {
            aimStartTick = currentTick;
        } else if (!isAiming) {
            aimStartTick = -1;
        }
    }

    public boolean isAiming() {
        return aiming;
    }

    public int getAimStartTick() {
        return aimStartTick;
    }
}
