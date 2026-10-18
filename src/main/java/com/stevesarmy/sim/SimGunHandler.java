package com.stevesarmy.sim;

import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.combat.ExposureCalculator;
import com.stevesarmy.combat.GunIntegration;
import com.stevesarmy.combat.cover.IncomingFireHandler;
import com.stevesarmy.entity.MachineGunnerEntity;
import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.entity.TargetEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The harness's stand-in gun so sim soldiers actually fight: every shot is a
 * hitscan ray that stops at blocks, damages the first living thing it crosses,
 * and feeds the exact same near-miss segment check TaCZ bullets use
 * ({@link IncomingFireHandler#checkNearMissLineSegment}) — so suppression on
 * both sides, weapon-threat intel, and fire-superiority tracking run against
 * live resistance instead of scripted injection. Magazine and reload cycles
 * are virtual (per-entity state, cleared between scenarios).
 *
 * Installed only while an armed scenario runs ({@link #setArmed}); outside
 * the harness the facade keeps its normal TaCZ/VPB/fallback routing.
 */
public final class SimGunHandler implements GunIntegration.GunHandler {

    public record ShotRecord(UUID shooterId, int tick, boolean aiming, float pitchDeviation,
                             float yawDeviation, float nativePitchDeviation,
                             float nativeYawDeviation, Vec3 aimPosition,
                             boolean movingIntent, Vec3 shooterPosition, Vec3 shooterVelocity,
                             com.stevesarmy.entity.ai.SoldierMovementController.FireMode controllerFireMode) {}

    private static final int RIFLE_MAG = 30;
    private static final int MG_MAG = 100;
    /** ~600 RPM. */
    private static final int SHOT_INTERVAL_TICKS = 2;
    private static final int RELOAD_TICKS = 60;
    private static final double RIFLE_RANGE = 64.0;
    private static final double MG_RANGE = 80.0;
    private static final float RIFLE_DAMAGE = 4.0f;
    private static final float MG_DAMAGE = 3.0f;
    /** Blocks/tick reported to suppression/intel — maps to max weapon threat. */
    private static final float BULLET_SPEED = 4.0f;
    private static final float[] RECOIL = {0.5f, 0.25f};

    // TaCZ speed-model emulation (production soldiers carry TaCZ guns, whose
    // LivingEntitySpeedModifier taxes MOVEMENT_SPEED for every gun holder).
    // Values are the default pack's AK47: weight 3.5 with SyncConfig's
    // 0.015/weight-unit MULTIPLY_BASE tax, and movement_speed {base 0.0,
    // aim -0.2, reload -0.15} applied MULTIPLY_TOTAL while aiming/reloading.
    // Without this the sim is blind to the ADS/reload slows that production
    // move-control code multiplies against.
    private static final double WEIGHT_TAX_PER_UNIT = 0.015;
    private static final double EMULATED_GUN_WEIGHT = 3.5;
    private static final double AIM_SPEED_MULT = -0.20;
    private static final double RELOAD_SPEED_MULT = -0.15;
    private static final java.util.UUID WEIGHT_MOD_UUID =
        java.util.UUID.fromString("7d1a2c40-5e8b-4c1a-9f6d-000000000001");
    private static final java.util.UUID AIM_MOD_UUID =
        java.util.UUID.fromString("7d1a2c40-5e8b-4c1a-9f6d-000000000002");
    private static final java.util.UUID RELOAD_MOD_UUID =
        java.util.UUID.fromString("7d1a2c40-5e8b-4c1a-9f6d-000000000003");

    private static SimGunHandler installed;
    private static boolean nativeScatterEnabled;
    private static final java.util.List<ShotRecord> acceptedShots = new java.util.ArrayList<>();

    private static final class GunState {
        int ammo;
        long nextShotTick;
        long reloadFinishTick;
        boolean reloading;
        boolean aiming;
        boolean m4a1MovementProfile;
    }

    /** Per-entity weapon state; cleared between scenarios by SimIsolation. */
    private static final Map<UUID, GunState> states = new HashMap<>();

    /** Movement attributes from the Test instance's default-pack M4A1 data.
     * Gun ballistics/cadence remain the harness's hitscan model. */
    public static void useM4A1MovementProfile(LivingEntity entity) {
        GunState state = refresh(entity);
        state.m4a1MovementProfile = true;
        AttributeInstance attr = entity.getAttribute(Attributes.MOVEMENT_SPEED);
        if (attr != null) {
            attr.removeModifier(AIM_MOD_UUID);
            attr.removeModifier(RELOAD_MOD_UUID);
        }
        applySpeedModifiers(entity, state);
    }

    /** Entities stripped via {@link #disarm}: the sim gun layer refuses them
     *  even though the virtual magazine still exists. */
    private static final java.util.Set<UUID> disarmed = new java.util.HashSet<>();

    /** Scenario seam for {@code disarmSoldier}: strips the virtual gun outright. */
    public static void disarm(LivingEntity entity) {
        disarmed.add(entity.getUUID());
        GunState state = states.remove(entity.getUUID());
        if (state != null) {
            AttributeInstance attr = entity.getAttribute(Attributes.MOVEMENT_SPEED);
            if (attr != null) {
                attr.removeModifier(WEIGHT_MOD_UUID);
                attr.removeModifier(AIM_MOD_UUID);
                attr.removeModifier(RELOAD_MOD_UUID);
            }
        }
    }

    public static void setArmed(boolean armed) {
        if (armed && installed == null) {
            installed = new SimGunHandler();
            states.clear();
            disarmed.clear();
            acceptedShots.clear();
            nativeScatterEnabled = false;
            GunIntegration.installSimGunHandler(installed);
        } else if (!armed && installed != null) {
            GunIntegration.uninstallSimGunHandler();
            installed = null;
            states.clear();
            disarmed.clear();
            acceptedShots.clear();
            nativeScatterEnabled = false;
        }
    }

    /** Scenario boundary: spawned entities must not inherit a previous run's magazine state. */
    public static void resetForSim() {
        states.clear();
        disarmed.clear();
        acceptedShots.clear();
        nativeScatterEnabled = false;
    }

    public static void enableNativeScatterForScenario() { nativeScatterEnabled = true; }

    public static java.util.List<ShotRecord> acceptedShots() { return java.util.List.copyOf(acceptedShots); }

    private static boolean isMg(LivingEntity entity) {
        return entity instanceof MachineGunnerEntity;
    }

    private static int magazineSizeOf(LivingEntity entity) {
        return isMg(entity) ? MG_MAG : RIFLE_MAG;
    }

    private static GunState refresh(LivingEntity entity) {
        GunState state = states.computeIfAbsent(entity.getUUID(), id -> {
            GunState s = new GunState();
            s.ammo = magazineSizeOf(entity);
            return s;
        });
        if (state.reloading && entity.tickCount >= state.reloadFinishTick) {
            state.reloading = false;
            state.ammo = magazineSizeOf(entity);
        }
        applySpeedModifiers(entity, state);
        return state;
    }

    /**
     * Mirrors TaCZ LivingEntitySpeedModifier.updateSpeedModifier for the sim
     * gun: permanent weight tax (MULTIPLY_BASE) plus the aim/reload multipliers
     * (MULTIPLY_TOTAL) keyed to the live state. Transient modifiers only, so
     * nothing leaks into saved data.
     */
    private static void applySpeedModifiers(LivingEntity entity, GunState state) {
        AttributeInstance attr = entity.getAttribute(Attributes.MOVEMENT_SPEED);
        if (attr == null) return;
        if (attr.getModifier(WEIGHT_MOD_UUID) == null) {
            attr.addTransientModifier(new AttributeModifier(WEIGHT_MOD_UUID,
                "Sim gun weight", -EMULATED_GUN_WEIGHT * WEIGHT_TAX_PER_UNIT,
                AttributeModifier.Operation.MULTIPLY_BASE));
        }
        boolean wantAim = state.aiming;
        if (wantAim && attr.getModifier(AIM_MOD_UUID) == null) {
            attr.addTransientModifier(new AttributeModifier(AIM_MOD_UUID,
                "Sim gun ADS", state.m4a1MovementProfile ? -0.15 : AIM_SPEED_MULT,
                AttributeModifier.Operation.MULTIPLY_TOTAL));
        } else if (!wantAim) {
            attr.removeModifier(AIM_MOD_UUID);
        }
        boolean wantReload = state.reloading;
        if (wantReload && attr.getModifier(RELOAD_MOD_UUID) == null) {
            attr.addTransientModifier(new AttributeModifier(RELOAD_MOD_UUID,
                "Sim gun reload", state.m4a1MovementProfile ? -0.10 : RELOAD_SPEED_MULT,
                AttributeModifier.Operation.MULTIPLY_TOTAL));
        } else if (!wantReload) {
            attr.removeModifier(RELOAD_MOD_UUID);
        }
    }

    private static float straightPitch(Vec3 eye, Vec3 targetPosition) {
        double dx = targetPosition.x - eye.x;
        double dy = targetPosition.y - eye.y;
        double dz = targetPosition.z - eye.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        return (float) -Math.toDegrees(Math.atan2(dy, horizontal));
    }

    private GunIntegration.ShootResult fire(LivingEntity shooter, Vec3 aimPosition,
                                            float pitchDeviation, float yawDeviation) {
        if (disarmed.contains(shooter.getUUID())) return GunIntegration.ShootResult.NOT_GUN;
        if (!(shooter.level() instanceof ServerLevel level)) {
            return GunIntegration.ShootResult.UNKNOWN;
        }
        GunState state = refresh(shooter);
        if (state.reloading) return GunIntegration.ShootResult.IS_RELOADING;
        if (shooter.tickCount < state.nextShotTick) return GunIntegration.ShootResult.COOLDOWN;
        if (state.ammo <= 0) return GunIntegration.ShootResult.NO_AMMO;

        float nativePitch = 0.0f;
        float nativeYaw = 0.0f;
        if (nativeScatterEnabled) {
            float halfAngle = getNativeInaccuracy(shooter, state.aiming);
            double radius = Math.sqrt(shooter.getRandom().nextDouble()) * halfAngle;
            double angle = shooter.getRandom().nextDouble() * Math.PI * 2.0;
            nativePitch = (float) (Math.sin(angle) * radius);
            nativeYaw = (float) (Math.cos(angle) * radius);
            pitchDeviation += nativePitch;
            yawDeviation += nativeYaw;
        }

        Vec3 eye = shooter.getEyePosition();
        double dx = aimPosition.x - shooter.getX();
        double dz = aimPosition.z - shooter.getZ();
        float pitch = straightPitch(eye, aimPosition) + pitchDeviation;
        float yaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90.0f + yawDeviation;

        double yawRad = Math.toRadians(yaw);
        double pitchRad = Math.toRadians(pitch);
        Vec3 dir = new Vec3(
            -Math.sin(yawRad) * Math.cos(pitchRad),
            -Math.sin(pitchRad),
            Math.cos(yawRad) * Math.cos(pitchRad)).normalize();

        Vec3 end = eye.add(dir.scale(isMg(shooter) ? MG_RANGE : RIFLE_RANGE));
        BlockHitResult blockHit = level.clip(new ClipContext(
            eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, shooter));
        Vec3 stop = blockHit.getType() != HitResult.Type.MISS ? blockHit.getLocation() : end;
        EntityHitResult entityHit = ProjectileUtil.getEntityHitResult(level, shooter, eye, stop,
            new AABB(eye, stop).inflate(1.0),
            e -> e.isAlive() && e instanceof LivingEntity
                && !(e instanceof TargetEntity) && e != shooter);

        state.ammo--;
        state.nextShotTick = shooter.tickCount + SHOT_INTERVAL_TICKS;
        acceptedShots.add(new ShotRecord(shooter.getUUID(), shooter.tickCount, state.aiming,
            pitchDeviation, yawDeviation, nativePitch, nativeYaw, aimPosition,
            shooter instanceof SoldierEntity s && s.getMovementController().hasMovingIntent(),
            shooter.position(), shooter.getDeltaMovement(),
            shooter instanceof SoldierEntity s ? s.getMovementController().fireMode() : null));

        if (entityHit != null) {
            LivingEntity victim = (LivingEntity) entityHit.getEntity();
            // A round that lands in a body goes no further — truncate the
            // near-miss segment there so targets behind the hit are not pinned.
            stop = entityHit.getLocation();
            victim.hurt(level.damageSources().generic(), isMg(shooter) ? MG_DAMAGE : RIFLE_DAMAGE);
        }

        IncomingFireHandler.checkNearMissLineSegment(level, eye, stop, BULLET_SPEED, shooter);
        if (shooter.tickCount < 400) {
            // Spawn-bleed forensics: broken fresh-world runs showed 1 hp / 10 t
            // intake + instant suppression at the squad spawn with ZERO tracer
            // taps — this names the shooter, the ray, and where the clip
            // stopped while the anomaly window is open.
            StevesArmyMod.LOGGER.info("[SimGun] t{} {} eye=({},{},{}) stop=({},{},{}) hit={} ammo={}",
                shooter.tickCount, shooter.getName().getString(),
                String.format("%.1f", eye.x), String.format("%.1f", eye.y), String.format("%.1f", eye.z),
                String.format("%.1f", stop.x), String.format("%.1f", stop.y), String.format("%.1f", stop.z),
                entityHit != null ? entityHit.getEntity().getName().getString() : "none",
                state.ammo);
        }
        return GunIntegration.ShootResult.SUCCESS;
    }

    @Override public boolean hasGun(LivingEntity entity) {
        return entity instanceof SoldierEntity && !disarmed.contains(entity.getUUID());
    }

    /** Mirrors the M249's TaCZ crawl_recoil_multiplier so crouch scenarios measure the real stance effect. */
    @Override public float getCrawlRecoilMultiplier(LivingEntity entity) { return 0.1f; }

    @Override public GunIntegration.ShootResult shoot(LivingEntity shooter, LivingEntity target) {
        if (target == null || !target.isAlive()) return GunIntegration.ShootResult.NO_TARGET;
        return fire(shooter, target.getEyePosition(), 0.0f, 0.0f);
    }

    @Override public GunIntegration.ShootResult shootWithDeviation(LivingEntity shooter,
                                                                   ExposureCalculator.AimPointResult aimPoint,
                                                                   float pitchDeviation, float yawDeviation) {
        if (!aimPoint.canShoot()) return GunIntegration.ShootResult.PATH_BLOCKED;
        return fire(shooter, aimPoint.position, pitchDeviation, yawDeviation);
    }

    @Override public GunIntegration.ShootResult shootAtPosition(LivingEntity shooter, Vec3 targetPosition) {
        return fire(shooter, targetPosition, 0.0f, 0.0f);
    }

    @Override public boolean canReload(LivingEntity entity) {
        GunState state = refresh(entity);
        return !state.reloading && state.ammo < magazineSizeOf(entity);
    }

    @Override public void reload(LivingEntity entity) {
        GunState state = refresh(entity);
        if (state.reloading || state.ammo >= magazineSizeOf(entity)) return;
        state.reloading = true;
        state.reloadFinishTick = entity.tickCount + RELOAD_TICKS;
        applySpeedModifiers(entity, state);
    }

    @Override public boolean isReloading(LivingEntity entity) { return refresh(entity).reloading; }
    @Override public int getCurrentAmmo(LivingEntity entity) { return refresh(entity).ammo; }
    @Override public int getMagazineSize(LivingEntity entity) { return magazineSizeOf(entity); }
    @Override public boolean hasAmmoInBarrel(LivingEntity entity) { return refresh(entity).ammo > 0; }

    @Override public void refillMagazine(LivingEntity entity) {
        GunState state = refresh(entity);
        state.reloading = false;
        state.ammo = magazineSizeOf(entity);
    }

    @Override public void cancelReload(LivingEntity entity) {
        GunState state = refresh(entity);
        state.reloading = false;
        applySpeedModifiers(entity, state);
    }

    @Override public long getShootCoolDown(LivingEntity entity) {
        long remaining = refresh(entity).nextShotTick - entity.tickCount;
        return remaining > 0 ? remaining * 50L : 0L;
    }

    // The aim-quality model gates shot timing; ADS is instant for the sim gun.
    @Override public float getAimProgress(LivingEntity entity) { return refresh(entity).aiming ? 1.0f : 0.0f; }

    @Override public void aim(LivingEntity entity, boolean isAiming) {
        GunState state = refresh(entity);
        state.aiming = isAiming;
        applySpeedModifiers(entity, state);
    }
    @Override public boolean isBolting(LivingEntity entity) { return false; }
    @Override public boolean isDrawing(LivingEntity entity) { return false; }
    @Override public void bolt(LivingEntity entity) {}
    @Override public boolean isManualBolt(LivingEntity entity) { return false; }
    @Override public boolean useInventoryAmmo(LivingEntity entity) { return false; }
    @Override public double getEffectiveRange(LivingEntity entity) {
        return isMg(entity) ? MG_RANGE : RIFLE_RANGE;
    }
    @Override public Optional<ItemStack> getGunStack(LivingEntity entity) { return Optional.empty(); }
    @Override public void initialData(LivingEntity entity) {}
    @Override public void draw(LivingEntity entity) {}
    @Override public int getCurrentAmmo(ItemStack gunStack) { return 0; }
    @Override public String getAmmoId(LivingEntity entity) { return "sim:ammo"; }
    @Override public String getAmmoId(ItemStack gunStack) { return "sim:ammo"; }
    @Override public int getAmmoCountForGun(ItemStack gunStack, ItemStack ammoStack) { return 0; }
    @Override public void lowCrouch(LivingEntity entity, boolean isLowCrouch) {}
    @Override public boolean isLowCrouching(LivingEntity entity) { return false; }
    @Override public float[] getGunRecoil(LivingEntity entity) { return RECOIL.clone(); }
    @Override public int getRPM(LivingEntity entity) { return 600; }
    @Override public float getBurstMinInterval(LivingEntity entity) { return 0.8f; }
    @Override public float getAimInaccuracy(LivingEntity entity) { return 0.15f; }
    @Override public GunIntegration.MovementProfile getMovementProfile(LivingEntity entity) {
        GunState state = refresh(entity);
        AttributeInstance attr = entity.getAttribute(Attributes.MOVEMENT_SPEED);
        if (attr == null) return new GunIntegration.MovementProfile(0.0, state.m4a1MovementProfile ? 0.85 : 0.80, false);
        double adsNeutral = attr.getValue();
        if (state.aiming) adsNeutral /= (1.0 + (state.m4a1MovementProfile ? -0.15 : AIM_SPEED_MULT));
        return new GunIntegration.MovementProfile(adsNeutral,
            state.m4a1MovementProfile ? 0.85 : 0.80, true);
    }
    @Override public float getNativeInaccuracy(LivingEntity entity, boolean aiming) {
        return aiming ? 0.25f : 3.0f;
    }
    @Override public float getAimPitch(LivingEntity entity, Vec3 targetPosition) {
        return straightPitch(entity.getEyePosition(), targetPosition);
    }
    @Override public GunIntegration.GunshotSignature getGunshotSignature(LivingEntity entity) {
        return GunIntegration.GunshotSignature.UNSUPPRESSED;
    }
    @Override public String getGunTabType(LivingEntity entity) { return isMg(entity) ? "mg" : "rifle"; }
    @Override public boolean isMachineGun(LivingEntity entity) { return isMg(entity); }
    @Override public String getGunId(LivingEntity entity) { return isMg(entity) ? "sim:mg" : "sim:rifle"; }
}
