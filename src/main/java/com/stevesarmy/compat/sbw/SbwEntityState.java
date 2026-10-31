package com.stevesarmy.compat.sbw;

import com.atsuishio.superbwarfare.data.gun.GunData;
import com.atsuishio.superbwarfare.data.gun.GunProp;
import com.atsuishio.superbwarfare.data.gun.value.ReloadState;
import com.stevesarmy.entity.SoldierEntity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks transient AI gun states (aim ramp, reload/bolt/draw timers, shoot cooldowns)
 * for soldiers holding Superb Warfare weapons.
 */
public final class SbwEntityState {
    private static final Map<UUID, SbwEntityState> STATES = new ConcurrentHashMap<>();

    private int reloadEndTick = -1;
    private int reloadToAdd = 0;
    private ItemStack reloadGunStack = ItemStack.EMPTY;
    private int boltEndTick = -1;
    private int drawEndTick = -1;
    private long nextShotTimeMs = -1;
    private int aimStartTick = -1;
    private boolean aiming = false;

    private SbwEntityState() {}

    public static SbwEntityState get(UUID uuid) {
        return STATES.computeIfAbsent(uuid, u -> new SbwEntityState());
    }

    public static void remove(UUID uuid) {
        STATES.remove(uuid);
    }

    public void tick(LivingEntity entity, GunData data) {
        int currentTick = entity.tickCount;

        if (reloadEndTick > 0 && currentTick >= reloadEndTick) {
            finishReload(entity, data);
        }

        if (boltEndTick > 0 && currentTick >= boltEndTick) {
            data.bolt.needed.set(false);
            data.bolt.actionTimer.set(0);
            boltEndTick = -1;
        }

        if (drawEndTick > 0 && currentTick >= drawEndTick) {
            drawEndTick = -1;
        }
    }

    public boolean isReloading(int currentTick) {
        return currentTick < reloadEndTick;
    }

    public void setReloading(int currentTick, int durationTicks, int toAdd, ItemStack stack) {
        this.reloadEndTick = currentTick + durationTicks;
        this.reloadToAdd = toAdd;
        this.reloadGunStack = stack;
    }

    public void cancelReload(LivingEntity entity, GunData data) {
        if (reloadEndTick > 0 && reloadToAdd > 0 && entity instanceof SoldierEntity soldier && !soldier.hasInfiniteReserveAmmo()) {
            SbwGunHandler.refundAmmoToInventory(soldier, data, reloadToAdd);
        }
        this.reloadEndTick = -1;
        this.reloadToAdd = 0;
        this.reloadGunStack = ItemStack.EMPTY;
        if (data != null) {
            data.reload.setState(ReloadState.NOT_RELOADING);
            data.virtualAmmo.set(0);
            data.save();
        }
    }

    private void finishReload(LivingEntity entity, GunData data) {
        int toAdd = this.reloadToAdd;
        this.reloadEndTick = -1;
        this.reloadToAdd = 0;
        this.reloadGunStack = ItemStack.EMPTY;

        if (toAdd > 0) {
            int current = data.ammo.get();
            int mag = data.get(GunProp.MAGAZINE);
            int newAmmo = Math.min(mag, current + toAdd);
            data.ammo.set(newAmmo);
            data.virtualAmmo.set(0);

            if (current == 0 && data.get(GunProp.BOLT_ACTION_TIME) > 0) {
                data.bolt.needed.set(false);
            }
        }
        data.reload.setState(ReloadState.NOT_RELOADING);
        data.save();
    }

    public boolean isBolting(int currentTick) {
        return currentTick < boltEndTick;
    }

    public void setBolting(int currentTick, int durationTicks) {
        this.boltEndTick = currentTick + durationTicks;
    }

    public boolean isDrawing(int currentTick) {
        return currentTick < drawEndTick;
    }

    public void setDrawing(int currentTick, int durationTicks) {
        this.drawEndTick = currentTick + durationTicks;
    }

    public long getRemainingShootCooldownMs(long nowMs) {
        if (nextShotTimeMs < 0) return 0;
        return Math.max(0, nextShotTimeMs - nowMs);
    }

    public void setShootCooldown(long nowMs, long cooldownMs) {
        this.nextShotTimeMs = cooldownMs > 0 ? nowMs + cooldownMs : -1;
    }

    public void setAiming(boolean isAiming, int currentTick) {
        this.aiming = isAiming;
        if (isAiming && aimStartTick < 0) {
            this.aimStartTick = currentTick;
        } else if (!isAiming) {
            this.aimStartTick = -1;
        }
    }

    public boolean isAiming() {
        return aiming;
    }

    public int getAimStartTick() {
        return aimStartTick;
    }
}
