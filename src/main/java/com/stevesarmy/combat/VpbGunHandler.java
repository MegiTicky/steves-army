package com.stevesarmy.combat;

import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.debug.DiagnosticLogManager;
import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.inventory.SoldierInventory;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.function.Supplier;

import static com.stevesarmy.combat.GunIntegration.GunshotSignature;
import static com.stevesarmy.combat.GunIntegration.ShootResult;

/**
 * Reflection-based gun handler for Vic's Point Blank (com.vicmatskiv.pointblank).
 *
 * VPB has no server-side "operator" API like TaCZ's IGunOperator. Stats are read
 * through public static getters on the ItemStack; firing is reimplemented on top of
 * VPB's public primitives (AmmoItem.createProjectile + SlowProjectile.shoot for
 * projectile ammo, HitScan + HurtingItem.hurtEntity for hitscan ammo). Reload and
 * gun state are tracked locally in VpbEntityState because VPB's GunClientState is
 * client-only and player-bound.
 */
public class VpbGunHandler implements GunIntegration.GunHandler {
    private static final double DEFAULT_GUN_RANGE = 50.0;
    private static final int DRAW_TICKS = 12;
    private static final int RELOAD_TICKS = 30;
    private static final float AIM_RAMP_TICKS = 10.0f;

    private static final Map<String, BallisticProfile> BALLISTIC_CACHE = new ConcurrentHashMap<>();

    private static volatile boolean resolved = false;
    private static Class<?> GUN_ITEM_CLASS;
    private static Class<?> FIRE_MODE_INSTANCE_CLASS;
    private static Class<?> AMMO_ITEM_CLASS;
    private static Class<?> SLOW_PROJECTILE_CLASS;
    private static Class<?> HURTING_ITEM_CLASS;
    private static Class<?> HIT_SCAN_CLASS;
    private static Class<?> SOUND_FEATURE_CLASS;
    private static Class<?> SOUND_DESCRIPTOR_CLASS;
    private static Class<?> FIRE_MODE_FEATURE_CLASS;
    private static Class<?> ACCURACY_FEATURE_CLASS;

    private static Method GUN_ITEM_GET_MAIN_HELD;
    private static Method GUN_ITEM_GET_FIRE_MODE;
    private static Method GUN_ITEM_GET_FIRE_MODES;
    private static Method GUN_ITEM_GET_AMMO;
    private static Method GUN_ITEM_SET_AMMO;
    private static Method GUN_ITEM_GET_MAX_CAPACITY;
    private static Method GUN_ITEM_INIT_STACK_FOR_CRAFTING;
    private static Method GUN_ITEM_GET_FIRE_SOUND;
    private static Method GUN_ITEM_GET_FIRE_SOUND_VOLUME;
    private static Method GUN_ITEM_GET_NAME;
    private static Method GUN_ITEM_GET_SELECTED_FIRE_MODE_TYPE;
    private static Method GUN_ITEM_DESTROY_PREDICATE;
    private static Method GUN_ITEM_PASS_PREDICATE;

    private static Method FIRE_MODE_GET_RPM;
    private static Method FIRE_MODE_GET_MAX_SHOOTING_DISTANCE;
    private static Method FIRE_MODE_GET_PELLET_COUNT;
    private static Method FIRE_MODE_GET_PELLET_SPREAD;
    private static Method FIRE_MODE_GET_AMMO;
    private static Method FIRE_MODE_GET_ACTUAL_AMMO;
    private static Method FIRE_MODE_GET_MAX_CAPACITY;
    private static Method FIRE_MODE_GET_BURST_SHOTS;
    private static Method FIRE_MODE_GET_TYPE;
    private static Method FIRE_MODE_IS_DEFAULT_AMMO_POOL;
    private static Method FIRE_MODE_GET_VIEW_SHAKE;

    private static Method AMMO_ITEM_CREATE_PROJECTILE;
    private static Method AMMO_ITEM_IS_HAS_PROJECTILE;
    private static Method AMMO_ITEM_GET_NAME;

    private static Method SLOW_PROJECTILE_SHOOT;
    private static Method SLOW_PROJECTILE_GET_GRAVITY;
    private static Method SLOW_PROJECTILE_GET_VELOCITY;
    private static Field SLOW_PROJECTILE_HIT_SCAN_TARGET;

    private static Method HURTING_ITEM_HURT_ENTITY;
    private static Method HIT_SCAN_GET_OBJECTS;

    private static Method FIRE_MODE_FEATURE_GET_RPM;
    private static Method FIRE_MODE_FEATURE_GET_MAX_DISTANCE;
    private static Method FIRE_MODE_FEATURE_GET_VIEW_SHAKE;
    private static Method ACCURACY_FEATURE_GET_MODIFIER;
    private static Method SOUND_FEATURE_GET_FIRE_SOUND;
    private static Method SOUND_DESCRIPTOR_SOUND_SUPPLIER;
    private static Method SOUND_DESCRIPTOR_VOLUME;

    public static boolean isVpbGun(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        if (!ensureResolved()) return false;
        try {
            return GUN_ITEM_CLASS.isInstance(stack.getItem());
        } catch (Exception e) {
            return false;
        }
    }

    private static synchronized boolean ensureResolved() {
        if (resolved) return true;
        try {
            GUN_ITEM_CLASS = Class.forName("com.vicmatskiv.pointblank.item.GunItem");
            FIRE_MODE_INSTANCE_CLASS = Class.forName("com.vicmatskiv.pointblank.item.FireModeInstance");
            AMMO_ITEM_CLASS = Class.forName("com.vicmatskiv.pointblank.item.AmmoItem");
            SLOW_PROJECTILE_CLASS = Class.forName("com.vicmatskiv.pointblank.entity.SlowProjectile");
            HURTING_ITEM_CLASS = Class.forName("com.vicmatskiv.pointblank.item.HurtingItem");
            HIT_SCAN_CLASS = Class.forName("com.vicmatskiv.pointblank.util.HitScan");
            SOUND_FEATURE_CLASS = Class.forName("com.vicmatskiv.pointblank.feature.SoundFeature");
            SOUND_DESCRIPTOR_CLASS = Class.forName("com.vicmatskiv.pointblank.feature.SoundFeature$SoundDescriptor");
            FIRE_MODE_FEATURE_CLASS = Class.forName("com.vicmatskiv.pointblank.feature.FireModeFeature");
            ACCURACY_FEATURE_CLASS = Class.forName("com.vicmatskiv.pointblank.feature.AccuracyFeature");

            GUN_ITEM_GET_MAIN_HELD = GUN_ITEM_CLASS.getMethod("getMainHeldGunItemStack", LivingEntity.class);
            GUN_ITEM_GET_FIRE_MODE = GUN_ITEM_CLASS.getMethod("getFireModeInstance", ItemStack.class);
            GUN_ITEM_GET_FIRE_MODES = GUN_ITEM_CLASS.getMethod("getFireModes", ItemStack.class);
            GUN_ITEM_GET_AMMO = GUN_ITEM_CLASS.getMethod("getAmmo", ItemStack.class, FIRE_MODE_INSTANCE_CLASS);
            GUN_ITEM_SET_AMMO = GUN_ITEM_CLASS.getMethod("setAmmo", ItemStack.class, FIRE_MODE_INSTANCE_CLASS, int.class);
            GUN_ITEM_GET_MAX_CAPACITY = GUN_ITEM_CLASS.getMethod("getMaxAmmoCapacity", ItemStack.class, FIRE_MODE_INSTANCE_CLASS);
            GUN_ITEM_INIT_STACK_FOR_CRAFTING = GUN_ITEM_CLASS.getMethod("initStackForCrafting", ItemStack.class);
            GUN_ITEM_GET_FIRE_SOUND = GUN_ITEM_CLASS.getMethod("getFireSound");
            GUN_ITEM_GET_FIRE_SOUND_VOLUME = GUN_ITEM_CLASS.getMethod("getFireSoundVolume");
            GUN_ITEM_GET_NAME = GUN_ITEM_CLASS.getMethod("getName");
            GUN_ITEM_GET_SELECTED_FIRE_MODE_TYPE = GUN_ITEM_CLASS.getMethod("getSelectedFireModeType", ItemStack.class);

            FIRE_MODE_GET_RPM = FIRE_MODE_INSTANCE_CLASS.getMethod("getRpm");
            FIRE_MODE_GET_MAX_SHOOTING_DISTANCE = FIRE_MODE_INSTANCE_CLASS.getMethod("getMaxShootingDistance");
            FIRE_MODE_GET_PELLET_COUNT = FIRE_MODE_INSTANCE_CLASS.getMethod("getPelletCount");
            FIRE_MODE_GET_PELLET_SPREAD = FIRE_MODE_INSTANCE_CLASS.getMethod("getPelletSpread");
            FIRE_MODE_GET_AMMO = FIRE_MODE_INSTANCE_CLASS.getMethod("getAmmo");
            FIRE_MODE_GET_ACTUAL_AMMO = FIRE_MODE_INSTANCE_CLASS.getMethod("getActualAmmo");
            FIRE_MODE_GET_MAX_CAPACITY = FIRE_MODE_INSTANCE_CLASS.getMethod("getMaxAmmoCapacity");
            FIRE_MODE_GET_BURST_SHOTS = FIRE_MODE_INSTANCE_CLASS.getMethod("getBurstShots");
            FIRE_MODE_GET_TYPE = FIRE_MODE_INSTANCE_CLASS.getMethod("getType");
            FIRE_MODE_IS_DEFAULT_AMMO_POOL = FIRE_MODE_INSTANCE_CLASS.getMethod("isUsingDefaultAmmoPool");
            FIRE_MODE_GET_VIEW_SHAKE = FIRE_MODE_INSTANCE_CLASS.getMethod("getViewShakeDescriptor");

            AMMO_ITEM_CREATE_PROJECTILE = AMMO_ITEM_CLASS.getMethod("createProjectile", LivingEntity.class, double.class, double.class, double.class);
            AMMO_ITEM_IS_HAS_PROJECTILE = AMMO_ITEM_CLASS.getMethod("isHasProjectile");
            AMMO_ITEM_GET_NAME = AMMO_ITEM_CLASS.getMethod("getName");

            SLOW_PROJECTILE_SHOOT = SLOW_PROJECTILE_CLASS.getMethod("shoot", double.class, double.class, double.class, double.class);
            SLOW_PROJECTILE_GET_GRAVITY = SLOW_PROJECTILE_CLASS.getMethod("getGravity");
            SLOW_PROJECTILE_GET_VELOCITY = SLOW_PROJECTILE_CLASS.getMethod("getInitialVelocityBlocksPerTick");
            SLOW_PROJECTILE_HIT_SCAN_TARGET = SLOW_PROJECTILE_CLASS.getDeclaredField("hitScanTarget");
            SLOW_PROJECTILE_HIT_SCAN_TARGET.setAccessible(true);

            HURTING_ITEM_HURT_ENTITY = HURTING_ITEM_CLASS.getMethod("hurtEntity", LivingEntity.class, EntityHitResult.class, Entity.class, ItemStack.class);
            HIT_SCAN_GET_OBJECTS = HIT_SCAN_CLASS.getMethod("getObjectsInCrosshair", LivingEntity.class, Vec3.class, Vec3.class,
                float.class, double.class, int.class, double.class, long.class, Predicate.class, Predicate.class, List.class);

            FIRE_MODE_FEATURE_GET_RPM = FIRE_MODE_FEATURE_CLASS.getMethod("getRpm", ItemStack.class);
            FIRE_MODE_FEATURE_GET_MAX_DISTANCE = FIRE_MODE_FEATURE_CLASS.getMethod("getMaxShootingDistance", ItemStack.class);
            FIRE_MODE_FEATURE_GET_VIEW_SHAKE = FIRE_MODE_FEATURE_CLASS.getMethod("getViewShakeDescriptor", ItemStack.class);
            ACCURACY_FEATURE_GET_MODIFIER = ACCURACY_FEATURE_CLASS.getMethod("getAccuracyModifier", ItemStack.class);

            SOUND_FEATURE_GET_FIRE_SOUND = SOUND_FEATURE_CLASS.getMethod("getFireSoundAndVolume", ItemStack.class);
            SOUND_DESCRIPTOR_SOUND_SUPPLIER = SOUND_DESCRIPTOR_CLASS.getMethod("soundSupplier");
            SOUND_DESCRIPTOR_VOLUME = SOUND_DESCRIPTOR_CLASS.getMethod("volume");

            resolved = true;
            return true;
        } catch (Exception e) {
            StevesArmyMod.LOGGER.warn("[VPB] Failed to resolve reflection targets: {}", e.toString());
            return false;
        }
    }

    // --- GunHandler implementation ---

    @Override
    public boolean hasGun(LivingEntity entity) {
        if (entity == null) return false;
        return isVpbGun(entity.getMainHandItem());
    }

    @Override
    public ShootResult shoot(LivingEntity shooter, LivingEntity target) {
        if (target == null || !target.isAlive()) return ShootResult.NO_TARGET;
        return fireAt(shooter, target.getEyePosition(), 0.0f, 0.0f);
    }

    @Override
    public ShootResult shootWithDeviation(LivingEntity shooter, ExposureCalculator.AimPointResult aimPoint, float pitchDeviation, float yawDeviation) {
        if (aimPoint == null) return ShootResult.NO_TARGET;
        if (!aimPoint.canShoot()) return ShootResult.PATH_BLOCKED;
        return fireAt(shooter, aimPoint.position, pitchDeviation, yawDeviation);
    }

    @Override
    public ShootResult shootAtPosition(LivingEntity shooter, Vec3 targetPosition) {
        if (targetPosition == null) return ShootResult.NO_TARGET;
        return fireAt(shooter, targetPosition, 0.0f, 0.0f);
    }

    private ShootResult fireAt(LivingEntity shooter, Vec3 aimPos, float pitchDeviation, float yawDeviation) {
        if (!hasGun(shooter)) return ShootResult.NOT_GUN;
        if (!ensureResolved()) return ShootResult.NOT_GUN;

        ItemStack gunStack = shooter.getMainHandItem();
        Object fireMode = resolveFireMode(gunStack);
        if (fireMode == null) return ShootResult.NOT_GUN;

        VpbEntityState state = VpbEntityState.get(shooter.getUUID());
        int tick = shooter.tickCount;
        if (state.isReloading(tick)) return ShootResult.IS_RELOADING;
        if (state.isDrawing(tick)) return ShootResult.IS_DRAWING;
        if (state.getRemainingShootCooldownMs(System.currentTimeMillis()) > 0) return ShootResult.COOLDOWN;

        int currentAmmo = getAmmoCount(gunStack, fireMode);
        if (currentAmmo <= 0) return ShootResult.NO_AMMO;

        Object ammo = getAmmoItem(fireMode);
        if (ammo == null) return ShootResult.NO_AMMO;

        Vec3 origin = shooter.getEyePosition();
        if (aimPos == null || !isFinite(aimPos) || !isFinite(origin)) return ShootResult.NO_TARGET;
        double dx = aimPos.x - origin.x;
        double dz = aimPos.z - origin.z;
        float basePitch = getAimPitch(shooter, aimPos);
        float baseYaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90.0F;
        Vec3 dir = lookVectorFromPitchYaw(basePitch + pitchDeviation, baseYaw + yawDeviation);
        if (!isFinite(dir)) {
            StevesArmyMod.LOGGER.debug("[VPB] fireAt skipped: non-finite aim direction, aimPos={}", aimPos);
            return ShootResult.UNKNOWN;
        }

        try {
            if (isProjectileAmmo(ammo)) {
                int pelletCount = getPelletCount(fireMode);
                int shots = Math.max(1, pelletCount);
                net.minecraft.core.Direction blockDir = net.minecraft.core.Direction.UP;
                for (int i = 0; i < shots; i++) {
                    Object projectile = AMMO_ITEM_CREATE_PROJECTILE.invoke(ammo, shooter, origin.x, origin.y, origin.z);
                    if (projectile == null) continue;
                    Vec3 shotDir = shots > 1 ? applySpread(dir, shooter.getRandom()) : dir;
                    double speed = (double) SLOW_PROJECTILE_GET_VELOCITY.invoke(projectile);
                    setProjectileHitScanTarget(projectile, aimPos, blockDir);
                    SLOW_PROJECTILE_SHOOT.invoke(projectile, shotDir.x, shotDir.y, shotDir.z, speed);
                    shooter.level().addFreshEntity((Entity) projectile);
                }
            } else {
                int maxDistance = getMaxShootingDistance(gunStack, fireMode);
                int shotCount = Math.max(1, getPelletCount(fireMode));
                List<BlockPos> destroyed = new ArrayList<>();
                Predicate<?> destroyPred = getDestroyPredicate(gunStack);
                Predicate<?> passPred = getPassThroughPredicate(gunStack);
                List<?> hits = (List<?>) HIT_SCAN_GET_OBJECTS.invoke(null, shooter, origin, dir,
                    0.0f, (double) maxDistance, shotCount, 0.0, 0L, destroyPred, passPred, destroyed);
                if (hits != null) {
                    for (Object hitObj : hits) {
                        if (hitObj instanceof HitResult hit && hit.getType() == HitResult.Type.ENTITY) {
                            hurtEntity(shooter, (EntityHitResult) hit, gunStack);
                        }
                    }
                }
            }
        } catch (Exception e) {
            StevesArmyMod.LOGGER.warn("[VPB] Fire failed: {}", e.toString());
            return ShootResult.UNKNOWN;
        }

        int maxCapacity = getMaxCapacity(gunStack, fireMode);
        if (maxCapacity < Integer.MAX_VALUE) {
            setAmmo(gunStack, fireMode, Math.max(0, currentAmmo - 1));
        }

        int rpm = getRpm(gunStack, fireMode);
        long cooldownMs = rpm > 0 ? Math.max(30L, 60000L / rpm) : 100L;
        state.setShootCooldown(System.currentTimeMillis(), cooldownMs);

        playFireSound(shooter, gunStack);
        return ShootResult.SUCCESS;
    }

    private void setProjectileHitScanTarget(Object projectile, Vec3 aimPos, net.minecraft.core.Direction blockDir) {
        if (SLOW_PROJECTILE_HIT_SCAN_TARGET == null) return;
        try {
            SLOW_PROJECTILE_HIT_SCAN_TARGET.set(projectile,
                new net.minecraft.world.phys.BlockHitResult(aimPos, blockDir, net.minecraft.core.BlockPos.containing(aimPos), false));
        } catch (Exception e) {
            StevesArmyMod.LOGGER.debug("[VPB] Failed to set projectile hitScanTarget: {}", e.toString());
        }
    }

    private void hurtEntity(LivingEntity shooter, EntityHitResult hit, ItemStack gunStack) {
        if (HURTING_ITEM_HURT_ENTITY == null) return;
        try {
            HURTING_ITEM_HURT_ENTITY.invoke(gunStack.getItem(), shooter, hit, null, gunStack);
        } catch (Exception e) {
            StevesArmyMod.LOGGER.debug("[VPB] hurtEntity failed: {}", e.toString());
        }
    }

    private Predicate<?> getDestroyPredicate(ItemStack gunStack) {
        try {
            if (GUN_ITEM_DESTROY_PREDICATE == null) {
                GUN_ITEM_DESTROY_PREDICATE = GUN_ITEM_CLASS.getDeclaredMethod("getDestroyBlockByHitScanPredicate");
                GUN_ITEM_DESTROY_PREDICATE.setAccessible(true);
            }
            Object p = GUN_ITEM_DESTROY_PREDICATE.invoke(gunStack.getItem());
            if (p instanceof Predicate<?> pred) return pred;
        } catch (Exception ignored) {
            // fall back to no destruction
        }
        return b -> false;
    }

    private Predicate<?> getPassThroughPredicate(ItemStack gunStack) {
        try {
            if (GUN_ITEM_PASS_PREDICATE == null) {
                GUN_ITEM_PASS_PREDICATE = GUN_ITEM_CLASS.getDeclaredMethod("getPassThroughBlocksByHitScanPredicate");
                GUN_ITEM_PASS_PREDICATE.setAccessible(true);
            }
            Object p = GUN_ITEM_PASS_PREDICATE.invoke(gunStack.getItem());
            if (p instanceof Predicate<?> pred) return pred;
        } catch (Exception ignored) {
            // fall back to no pass-through
        }
        return b -> false;
    }

    private void playFireSound(LivingEntity shooter, ItemStack gunStack) {
        try {
            SoundEvent sound = null;
            float volume = 0.0f;

            if (ensureResolved() && SOUND_FEATURE_GET_FIRE_SOUND != null) {
                try {
                    Object descriptor = SOUND_FEATURE_GET_FIRE_SOUND.invoke(null, gunStack);
                    if (descriptor != null) {
                        Supplier<?> supplier = (Supplier<?>) SOUND_DESCRIPTOR_SOUND_SUPPLIER.invoke(descriptor);
                        Object supplied = supplier != null ? supplier.get() : null;
                        if (supplied instanceof SoundEvent se) {
                            sound = se;
                            volume = (float) SOUND_DESCRIPTOR_VOLUME.invoke(descriptor);
                        }
                    }
                } catch (Exception ignored) {
                    // fall through to GunItem fields
                }
            }

            if (sound == null && GUN_ITEM_GET_FIRE_SOUND != null) {
                Object fieldSound = GUN_ITEM_GET_FIRE_SOUND.invoke(gunStack.getItem());
                if (fieldSound instanceof SoundEvent se) {
                    sound = se;
                    volume = (float) GUN_ITEM_GET_FIRE_SOUND_VOLUME.invoke(gunStack.getItem());
                }
            }

            if (sound == null || volume <= 0.0f) return;
            shooter.level().playSound(null, shooter.getX(), shooter.getY(), shooter.getZ(), sound, SoundSource.NEUTRAL, volume, 1.0f);
        } catch (Exception e) {
            StevesArmyMod.LOGGER.debug("[VPB] playFireSound failed: {}", e.toString());
        }
    }

    @Override
    public boolean canReload(LivingEntity entity) {
        if (!hasGun(entity)) return false;
        ItemStack gunStack = entity.getMainHandItem();
        Object fireMode = resolveFireMode(gunStack);
        if (fireMode == null) return false;
        int capacity = getMaxCapacity(gunStack, fireMode);
        int current = getAmmoCount(gunStack, fireMode);
        if (current >= capacity) return false;
        if (isDefaultAmmoPool(fireMode)) return true;
        if (entity instanceof SoldierEntity soldier) {
            return countCompatibleAmmo(soldier, gunStack, fireMode) > 0;
        }
        return false;
    }

    @Override
    public void reload(LivingEntity entity) {
        if (!hasGun(entity)) return;
        ItemStack gunStack = entity.getMainHandItem();
        Object fireMode = resolveFireMode(gunStack);
        if (fireMode == null) return;

        int capacity = getMaxCapacity(gunStack, fireMode);
        int current = getAmmoCount(gunStack, fireMode);
        int needed = Math.max(0, capacity - current);
        int found = 0;

        if (isDefaultAmmoPool(fireMode)
            || (entity instanceof SoldierEntity s && s.hasInfiniteReserveAmmo())) {
            found = needed;
        } else if (entity instanceof SoldierEntity soldier) {
            found = consumeCompatibleAmmo(soldier, gunStack, fireMode, needed);
        }

        if (found > 0) {
            setAmmo(gunStack, fireMode, current + found);
        }

        VpbEntityState state = VpbEntityState.get(entity.getUUID());
        state.setReloading(entity.tickCount, RELOAD_TICKS);
    }

    private int countCompatibleAmmo(SoldierEntity soldier, ItemStack gunStack, Object fireMode) {
        SoldierInventory inv = soldier.getSoldierInventory();
        if (inv == null) return 0;
        int total = 0;
        for (int i = SoldierInventory.SLOT_GENERAL_START; i < SoldierInventory.INVENTORY_SIZE; i++) {
            ItemStack slot = inv.getItem(i);
            if (slot.isEmpty()) continue;
            if (isCompatibleAmmo(slot, fireMode)) total += slot.getCount();
        }
        return total;
    }

    private int consumeCompatibleAmmo(SoldierEntity soldier, ItemStack gunStack, Object fireMode, int needed) {
        SoldierInventory inv = soldier.getSoldierInventory();
        if (inv == null || needed <= 0) return 0;
        int remaining = needed;
        int consumed = 0;
        for (int i = SoldierInventory.SLOT_GENERAL_START; i < SoldierInventory.INVENTORY_SIZE && remaining > 0; i++) {
            ItemStack slot = inv.getItem(i);
            if (slot.isEmpty()) continue;
            if (!isCompatibleAmmo(slot, fireMode)) continue;
            int take = Math.min(slot.getCount(), remaining);
            inv.removeItem(i, take);
            consumed += take;
            remaining -= take;
        }
        return consumed;
    }

    private boolean isCompatibleAmmo(ItemStack ammoStack, Object fireMode) {
        if (ammoStack.isEmpty()) return false;
        List<?> actual = getActualAmmo(fireMode);
        if (actual == null || actual.isEmpty()) return false;
        Item item = ammoStack.getItem();
        for (Object ammo : actual) {
            if (ammo instanceof Item a && a == item) return true;
        }
        return false;
    }

    @Override
    public void refillMagazine(LivingEntity entity) {
        if (!hasGun(entity)) return;
        ItemStack gunStack = entity.getMainHandItem();
        Object fireMode = resolveFireMode(gunStack);
        if (fireMode == null) return;
        int capacity = getMaxCapacity(gunStack, fireMode);
        if (capacity > 0) setAmmo(gunStack, fireMode, capacity);
        VpbEntityState state = VpbEntityState.get(entity.getUUID());
        state.cancelReload();
    }

    @Override
    public void cancelReload(LivingEntity entity) {
        VpbEntityState.get(entity.getUUID()).cancelReload();
    }

    @Override
    public void bolt(LivingEntity entity) {
        // VPB cycles via fire modes; no bolt action to drive.
    }

    @Override
    public void aim(LivingEntity entity, boolean isAiming) {
        ItemStack gunStack = entity.getMainHandItem();
        if (!gunStack.isEmpty() && isVpbGun(gunStack)) {
            gunStack.getOrCreateTag().putBoolean("aim", isAiming);
        }
        VpbEntityState.get(entity.getUUID()).setAiming(isAiming, entity.tickCount);
    }

    @Override
    public boolean isBolting(LivingEntity entity) {
        return false;
    }

    @Override
    public boolean isReloading(LivingEntity entity) {
        return VpbEntityState.get(entity.getUUID()).isReloading(entity.tickCount);
    }

    @Override
    public float getAimProgress(LivingEntity entity) {
        VpbEntityState state = VpbEntityState.get(entity.getUUID());
        if (!state.isAiming()) return 0.0f;
        int elapsed = entity.tickCount - state.getAimStartTick();
        return Math.min(1.0f, elapsed / AIM_RAMP_TICKS);
    }

    @Override
    public long getShootCoolDown(LivingEntity entity) {
        return VpbEntityState.get(entity.getUUID()).getRemainingShootCooldownMs(System.currentTimeMillis());
    }

    @Override
    public boolean isDrawing(LivingEntity entity) {
        return VpbEntityState.get(entity.getUUID()).isDrawing(entity.tickCount);
    }

    @Override
    public double getEffectiveRange(LivingEntity entity) {
        if (!ensureResolved()) return DEFAULT_GUN_RANGE;
        ItemStack gunStack = entity.getMainHandItem();
        Object fireMode = resolveFireMode(gunStack);
        if (fireMode == null) return DEFAULT_GUN_RANGE;
        int dist = getMaxShootingDistance(gunStack, fireMode);
        return dist > 0 ? dist : DEFAULT_GUN_RANGE;
    }

    @Override
    public Optional<ItemStack> getGunStack(LivingEntity entity) {
        ItemStack main = entity.getMainHandItem();
        return isVpbGun(main) ? Optional.of(main) : Optional.empty();
    }

    @Override
    public void initialData(LivingEntity entity) {
        if (!ensureResolved()) return;
        ItemStack gunStack = entity.getMainHandItem();
        if (gunStack.isEmpty() || !isVpbGun(gunStack)) return;
        try {
            GUN_ITEM_INIT_STACK_FOR_CRAFTING.invoke(null, gunStack);
        } catch (Exception e) {
            if (DiagnosticLogManager.isDamageLoggingEnabled()) {
                StevesArmyMod.LOGGER.debug("[VPB] initialData failed: {}", e.toString());
            }
        }
    }

    @Override
    public void draw(LivingEntity entity) {
        if (!hasGun(entity)) return;
        VpbEntityState.get(entity.getUUID()).setDrawing(entity.tickCount, DRAW_TICKS);
    }

    @Override
    public int getMagazineSize(LivingEntity entity) {
        ItemStack gunStack = entity.getMainHandItem();
        Object fireMode = resolveFireMode(gunStack);
        if (fireMode == null) return 30;
        return getMaxCapacity(gunStack, fireMode);
    }

    @Override
    public int getCurrentAmmo(LivingEntity entity) {
        return getCurrentAmmo(entity.getMainHandItem());
    }

    @Override
    public boolean hasAmmoInBarrel(LivingEntity entity) {
        return getCurrentAmmo(entity) > 0;
    }

    @Override
    public boolean isManualBolt(LivingEntity entity) {
        return false;
    }

    @Override
    public boolean useInventoryAmmo(LivingEntity entity) {
        ItemStack gunStack = entity.getMainHandItem();
        Object fireMode = resolveFireMode(gunStack);
        if (fireMode == null) return false;
        return !isDefaultAmmoPool(fireMode);
    }

    @Override
    public String getGunId(LivingEntity entity) {
        return getGunId(entity.getMainHandItem());
    }

    private String getGunId(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return "";
        if (isVpbGun(stack)) {
            ResourceLocation rl = net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(stack.getItem());
            if (rl != null) return rl.toString();
        }
        try {
            Object name = GUN_ITEM_GET_NAME.invoke(stack.getItem());
            return name != null ? name.toString() : "";
        } catch (Exception e) {
            return "";
        }
    }

    @Override
    public String getAmmoId(LivingEntity entity) {
        return getAmmoIdFromStack(entity.getMainHandItem());
    }

    private String getAmmoIdFromStack(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return "";
        Object fireMode = resolveFireMode(stack);
        if (fireMode == null) return "";
        Object ammo = getAmmoItem(fireMode);
        if (ammo == null) return "";
        if (ammo instanceof Item item) {
            ResourceLocation rl = net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(item);
            if (rl != null) return rl.toString();
        }
        String name = getAmmoName(ammo);
        return name != null ? name : "";
    }

    @Override
    public int getCurrentAmmo(ItemStack gunStack) {
        Object fireMode = resolveFireMode(gunStack);
        if (fireMode == null) return 0;
        return getAmmoCount(gunStack, fireMode);
    }

    @Override
    public String getAmmoId(ItemStack gunStack) {
        return getAmmoIdFromStack(gunStack);
    }

    @Override
    public int getAmmoCountForGun(ItemStack gunStack, ItemStack ammoStack) {
        if (gunStack == null || ammoStack == null || gunStack.isEmpty() || ammoStack.isEmpty()) return 0;
        Object fireMode = resolveFireMode(gunStack);
        if (fireMode == null) return 0;
        if (isDefaultAmmoPool(fireMode)) return 9999;
        return isCompatibleAmmo(ammoStack, fireMode) ? ammoStack.getCount() : 0;
    }

    @Override
    public void lowCrouch(LivingEntity entity, boolean isLowCrouch) {
        if (entity instanceof SoldierEntity soldier) {
            soldier.setLowCrouching(isLowCrouch);
        } else {
            if (isLowCrouch) {
                entity.setPose(net.minecraft.world.entity.Pose.SWIMMING);
            } else if (entity.getPose() == net.minecraft.world.entity.Pose.SWIMMING) {
                entity.setPose(net.minecraft.world.entity.Pose.STANDING);
            }
        }
    }

    @Override
    public boolean isLowCrouching(LivingEntity entity) {
        if (entity instanceof SoldierEntity soldier) {
            return soldier.isLowCrouching();
        }
        return entity.getPose() == net.minecraft.world.entity.Pose.SWIMMING && !entity.isInWater();
    }

    @Override
    public float[] getGunRecoil(LivingEntity entity) {
        ItemStack gunStack = entity.getMainHandItem();
        Object descriptor = getViewShakeDescriptor(gunStack);
        if (descriptor != null) {
            try {
                Method amplitude = descriptor.getClass().getMethod("amplitude");
                Object amp = amplitude.invoke(descriptor);
                double a = amp instanceof Number n ? n.doubleValue() : 0.5;
                return new float[]{(float) a, (float) (a * 0.5)};
            } catch (Exception e) {
                return new float[]{0.5f, 0.25f};
            }
        }
        return new float[]{0.5f, 0.25f};
    }

    private Object getViewShakeDescriptor(ItemStack stack) {
        if (!ensureResolved() || stack == null || stack.isEmpty()) return null;
        if (FIRE_MODE_FEATURE_GET_VIEW_SHAKE != null) {
            try {
                return FIRE_MODE_FEATURE_GET_VIEW_SHAKE.invoke(null, stack);
            } catch (Exception e) {
                // fall back to fire mode instance
            }
        }
        Object fireMode = resolveFireMode(stack);
        if (fireMode == null || FIRE_MODE_GET_VIEW_SHAKE == null) return null;
        try {
            return FIRE_MODE_GET_VIEW_SHAKE.invoke(fireMode);
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public int getRPM(LivingEntity entity) {
        ItemStack gunStack = entity.getMainHandItem();
        Object fireMode = resolveFireMode(gunStack);
        return getRpm(gunStack, fireMode);
    }

    private int getRpm(ItemStack stack, Object fireMode) {
        if (ensureResolved() && FIRE_MODE_FEATURE_GET_RPM != null) {
            try {
                return (int) FIRE_MODE_FEATURE_GET_RPM.invoke(null, stack);
            } catch (Exception e) {
                // fall through
            }
        }
        if (fireMode != null && FIRE_MODE_GET_RPM != null) {
            try {
                return (int) FIRE_MODE_GET_RPM.invoke(fireMode);
            } catch (Exception e) {
                // fall through
            }
        }
        return 600;
    }

    @Override
    public float getBurstMinInterval(LivingEntity entity) {
        ItemStack gunStack = entity.getMainHandItem();
        Object fireMode = resolveFireMode(gunStack);
        if (fireMode == null) return 0.8f;
        int burstShots = getBurstShots(fireMode);
        int rpm = getRpm(gunStack, fireMode);
        if (rpm <= 0) return 0.8f;
        float seconds = burstShots * 60.0f / rpm;
        return Math.max(0.25f, seconds);
    }

    private int getBurstShots(Object fireMode) {
        if (fireMode == null || FIRE_MODE_GET_BURST_SHOTS == null) return 1;
        try {
            return (int) FIRE_MODE_GET_BURST_SHOTS.invoke(fireMode);
        } catch (Exception e) {
            return 1;
        }
    }

    @Override
    public float getAimInaccuracy(LivingEntity entity) {
        ItemStack gunStack = entity.getMainHandItem();
        float base = 0.15f;
        if (ensureResolved() && ACCURACY_FEATURE_GET_MODIFIER != null) {
            try {
                Object modifier = ACCURACY_FEATURE_GET_MODIFIER.invoke(null, gunStack);
                if (modifier instanceof Number n) {
                    base *= n.floatValue();
                }
            } catch (Exception e) {
                // use default
            }
        }
        return Math.max(0.02f, Math.min(base, 1.0f));
    }

    @Override
    public float getAimPitch(LivingEntity shooter, Vec3 targetPosition) {
        float fallback = straightPitch(shooter, targetPosition);
        if (!ensureResolved() || shooter == null || targetPosition == null) return fallback;
        ItemStack gunStack = shooter.getMainHandItem();
        Object fireMode = resolveFireMode(gunStack);
        if (fireMode == null) return fallback;
        Object ammo = getAmmoItem(fireMode);
        if (ammo == null || !isProjectileAmmo(ammo)) return fallback;

        BallisticProfile profile = getOrProbeBallistic(shooter, ammo);
        if (profile == null) return fallback;

        Vec3 origin = shooter.getEyePosition();
        double dx = targetPosition.x - origin.x;
        double dz = targetPosition.z - origin.z;
        double horizontalDistance = Math.sqrt(dx * dx + dz * dz);
        if (horizontalDistance < 0.05 || !Double.isFinite(horizontalDistance)) return fallback;

        double verticalDistance = targetPosition.y - origin.y;
        Double elevation = solveBallisticElevation(horizontalDistance, verticalDistance, profile);
        if (elevation == null) return fallback;

        float pitch = (float) -Math.toDegrees(elevation);
        return Float.isFinite(pitch) ? pitch : fallback;
    }

    private BallisticProfile getOrProbeBallistic(LivingEntity shooter, Object ammo) {
        String ammoName = getAmmoName(ammo);
        if (ammoName != null && !ammoName.isEmpty()) {
            BallisticProfile cached = BALLISTIC_CACHE.get(ammoName);
            if (cached != null) return cached;
        }
        try {
            Object projectile = AMMO_ITEM_CREATE_PROJECTILE.invoke(ammo, shooter, shooter.getX(), shooter.getY(), shooter.getZ());
            if (projectile == null) return null;
            double gravity = (double) SLOW_PROJECTILE_GET_GRAVITY.invoke(projectile);
            double speed = (double) SLOW_PROJECTILE_GET_VELOCITY.invoke(projectile);
            ((Entity) projectile).discard();
            if (!Double.isFinite(speed) || !Double.isFinite(gravity) || speed <= 0.0) return null;
            BallisticProfile profile = new BallisticProfile(speed, gravity);
            if (ammoName != null && !ammoName.isEmpty()) BALLISTIC_CACHE.put(ammoName, profile);
            return profile;
        } catch (Exception e) {
            if (DiagnosticLogManager.isDamageLoggingEnabled()) {
                StevesArmyMod.LOGGER.debug("[VPB] Ballistic probe failed: {}", e.toString());
            }
            return null;
        }
    }

    private static Double solveBallisticElevation(double horizontalDistance, double verticalDistance, BallisticProfile profile) {
        final double minimumElevation = Math.toRadians(-30.0);
        final double maximumElevation = Math.toRadians(75.0);
        final int samples = 42;
        double previousAngle = minimumElevation;
        double previousError = trajectoryHeightAtDistance(horizontalDistance, previousAngle, profile) - verticalDistance;
        if (!Double.isFinite(previousError)) return null;

        for (int i = 1; i <= samples; i++) {
            double angle = minimumElevation + (maximumElevation - minimumElevation) * i / samples;
            double error = trajectoryHeightAtDistance(horizontalDistance, angle, profile) - verticalDistance;
            if (!Double.isFinite(error)) {
                previousAngle = angle;
                previousError = error;
                continue;
            }
            if (Math.abs(error) < 0.02) return angle;
            if (previousError * error < 0.0) {
                double low = previousAngle;
                double high = angle;
                double lowError = previousError;
                for (int iteration = 0; iteration < 18; iteration++) {
                    double midpoint = (low + high) * 0.5;
                    double midpointError = trajectoryHeightAtDistance(horizontalDistance, midpoint, profile) - verticalDistance;
                    if (!Double.isFinite(midpointError)) return null;
                    if (Math.abs(midpointError) < 0.005) return midpoint;
                    if (lowError * midpointError <= 0.0) {
                        high = midpoint;
                    } else {
                        low = midpoint;
                        lowError = midpointError;
                    }
                }
                return (low + high) * 0.5;
            }
            previousAngle = angle;
            previousError = error;
        }
        return null;
    }

    /** VPB DirectAttackTrajectory physics: position advances by velocity, then velocity += gravity (data gravity is negative => drop). */
    private static double trajectoryHeightAtDistance(double horizontalDistance, double elevation, BallisticProfile profile) {
        double horizontalVelocity = profile.speedBlocksPerTick * Math.cos(elevation);
        double verticalVelocity = profile.speedBlocksPerTick * Math.sin(elevation);
        double horizontalPosition = 0.0;
        double verticalPosition = 0.0;
        double previousHorizontal = 0.0;
        double previousVertical = 0.0;
        for (int tick = 0; tick < 400; tick++) {
            previousHorizontal = horizontalPosition;
            previousVertical = verticalPosition;
            horizontalPosition += horizontalVelocity;
            verticalPosition += verticalVelocity;
            if (horizontalPosition >= horizontalDistance) {
                double fraction = (horizontalDistance - previousHorizontal)
                    / Math.max(horizontalPosition - previousHorizontal, 1.0E-9);
                return previousVertical + (verticalPosition - previousVertical) * fraction;
            }
            verticalVelocity += profile.gravity;
        }
        return Double.NaN;
    }

    private static float straightPitch(LivingEntity shooter, Vec3 targetPosition) {
        Vec3 origin = shooter.getEyePosition();
        double dx = targetPosition.x - origin.x;
        double dy = targetPosition.y - origin.y;
        double dz = targetPosition.z - origin.z;
        double horizontalDistance = Math.sqrt(dx * dx + dz * dz);
        return (float) -Math.toDegrees(Math.atan2(dy, horizontalDistance));
    }

    private static Vec3 lookVectorFromPitchYaw(float pitch, float yaw) {
        float yawRad = (float) Math.toRadians(yaw);
        float pitchRad = (float) Math.toRadians(pitch);
        double x = -Math.sin(yawRad) * Math.cos(pitchRad);
        double y = -Math.sin(pitchRad);
        double z = Math.cos(yawRad) * Math.cos(pitchRad);
        return new Vec3(x, y, z);
    }

    private static Vec3 applySpread(Vec3 dir, RandomSource random) {
        double spread = 0.03;
        Vec3 up = new Vec3(0, 1, 0);
        Vec3 right = dir.cross(up);
        if (right.lengthSqr() < 1.0E-6) right = new Vec3(1, 0, 0);
        right = right.normalize();
        Vec3 upP = right.cross(dir).normalize();
        double dPitch = (random.nextDouble() - 0.5) * 2.0 * spread;
        double dYaw = (random.nextDouble() - 0.5) * 2.0 * spread;
        return dir.add(right.scale(dYaw)).add(upP.scale(dPitch)).normalize();
    }

    @Override
    public GunshotSignature getGunshotSignature(LivingEntity entity) {
        return GunshotSignature.UNSUPPRESSED;
    }

    @Override
    public String getGunTabType(LivingEntity entity) {
        ItemStack gunStack = entity.getMainHandItem();
        Object fireMode = resolveFireMode(gunStack);
        if (fireMode == null) return "rifle";
        String fireModeName = getFireModeTypeName(fireMode);
        int rpm = getRpm(gunStack, fireMode);
        int mag = getMaxCapacity(gunStack, fireMode);
        if ("AUTOMATIC".equals(fireModeName) && rpm >= 500 && mag >= 30) {
            return "machine_gun";
        }
        return "rifle";
    }

    private String getFireModeTypeName(Object fireMode) {
        if (fireMode == null || FIRE_MODE_GET_TYPE == null) return "";
        try {
            Object type = FIRE_MODE_GET_TYPE.invoke(fireMode);
            return type != null ? type.toString() : "";
        } catch (Exception e) {
            return "";
        }
    }

    @Override
    public boolean isMachineGun(LivingEntity entity) {
        String tabType = getGunTabType(entity);
        return "machine_gun".equals(tabType) || "mg".equals(tabType) || "lmg".equals(tabType)
            || "mmg".equals(tabType) || "hmg".equals(tabType) || "smg".equals(tabType);
    }

    // --- reflection helpers ---

    private Object resolveFireMode(ItemStack stack) {
        if (!ensureResolved() || stack == null || stack.isEmpty()) return null;
        try {
            Object fm = GUN_ITEM_GET_FIRE_MODE.invoke(null, stack);
            if (fm != null) return fm;
            Object fireModes = GUN_ITEM_GET_FIRE_MODES.invoke(null, stack);
            if (fireModes instanceof List<?> list && !list.isEmpty()) return list.get(0);
        } catch (Exception e) {
            // ignore
        }
        return null;
    }

    private Object getAmmoItem(Object fireMode) {
        if (fireMode == null || FIRE_MODE_GET_AMMO == null) return null;
        try {
            return FIRE_MODE_GET_AMMO.invoke(fireMode);
        } catch (Exception e) {
            return null;
        }
    }

    private List<?> getActualAmmo(Object fireMode) {
        if (fireMode == null || FIRE_MODE_GET_ACTUAL_AMMO == null) return List.of();
        try {
            Object list = FIRE_MODE_GET_ACTUAL_AMMO.invoke(fireMode);
            return list instanceof List<?> l ? l : List.of();
        } catch (Exception e) {
            return List.of();
        }
    }

    private boolean isDefaultAmmoPool(Object fireMode) {
        if (fireMode == null || FIRE_MODE_IS_DEFAULT_AMMO_POOL == null) return false;
        try {
            return (boolean) FIRE_MODE_IS_DEFAULT_AMMO_POOL.invoke(fireMode);
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isProjectileAmmo(Object ammo) {
        if (ammo == null || AMMO_ITEM_IS_HAS_PROJECTILE == null) return false;
        try {
            return (boolean) AMMO_ITEM_IS_HAS_PROJECTILE.invoke(ammo);
        } catch (Exception e) {
            return false;
        }
    }

    private int getAmmoCount(ItemStack stack, Object fireMode) {
        if (!ensureResolved() || stack == null || fireMode == null) return 0;
        try {
            return (int) GUN_ITEM_GET_AMMO.invoke(null, stack, fireMode);
        } catch (Exception e) {
            return 0;
        }
    }

    private void setAmmo(ItemStack stack, Object fireMode, int ammo) {
        if (!ensureResolved() || stack == null || fireMode == null) return;
        try {
            GUN_ITEM_SET_AMMO.invoke(null, stack, fireMode, ammo);
        } catch (Exception e) {
            StevesArmyMod.LOGGER.debug("[VPB] setAmmo failed: {}", e.toString());
        }
    }

    private int getMaxCapacity(ItemStack stack, Object fireMode) {
        if (!ensureResolved() || stack == null || fireMode == null) return 0;
        try {
            return (int) GUN_ITEM_GET_MAX_CAPACITY.invoke(stack.getItem(), stack, fireMode);
        } catch (Exception e) {
            return 0;
        }
    }

    private int getPelletCount(Object fireMode) {
        if (fireMode == null || FIRE_MODE_GET_PELLET_COUNT == null) return 1;
        try {
            return (int) FIRE_MODE_GET_PELLET_COUNT.invoke(fireMode);
        } catch (Exception e) {
            return 1;
        }
    }

    private int getMaxShootingDistance(ItemStack stack, Object fireMode) {
        if (ensureResolved() && FIRE_MODE_FEATURE_GET_MAX_DISTANCE != null) {
            try {
                return (int) FIRE_MODE_FEATURE_GET_MAX_DISTANCE.invoke(null, stack);
            } catch (Exception e) {
                // fall through
            }
        }
        if (fireMode != null && FIRE_MODE_GET_MAX_SHOOTING_DISTANCE != null) {
            try {
                return (int) FIRE_MODE_GET_MAX_SHOOTING_DISTANCE.invoke(fireMode);
            } catch (Exception e) {
                // fall through
            }
        }
        return (int) DEFAULT_GUN_RANGE;
    }

    private String getAmmoName(Object ammo) {
        if (ammo == null || AMMO_ITEM_GET_NAME == null) return null;
        try {
            Object name = AMMO_ITEM_GET_NAME.invoke(ammo);
            return name != null ? name.toString() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean isFinite(Vec3 v) {
        return v != null && Double.isFinite(v.x) && Double.isFinite(v.y) && Double.isFinite(v.z);
    }

    private record BallisticProfile(double speedBlocksPerTick, double gravity) {}
}
