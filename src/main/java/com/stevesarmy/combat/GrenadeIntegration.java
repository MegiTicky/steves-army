package com.stevesarmy.combat;

import com.stevesarmy.StevesArmyConfig;
import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.debug.DiagnosticLogManager;
import com.stevesarmy.inventory.SoldierInventory;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.fml.ModList;

import javax.annotation.Nullable;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.world.level.Explosion;

/** Optional LesRaisins Tactical Equipments integration. */
public final class GrenadeIntegration {
    private static final String MOD_ID = "lrtactical";
    /** LesRaisins registers one generic item; the actual throwable is stored in its NBT. */
    private static final String THROWABLE_ITEM_ID = MOD_ID + ":throwable";
    private static final String THROWABLE_ID_TAG = "ThrowableId";
    private static final Set<String> SUPPORTED_IDS = Set.of("lrtactical:m67", "lrtactical:rgn");
    private static boolean initialized;
    private static boolean available;
    private static boolean failureLogged;
    private static Class<?> throwableInterface;
    private static Method throwableOf;
    private static Method getId;
    private static Method getThrowableIndex;
    private static Method onThrow;
    private static final Map<UUID, GrenadeDiagnostic> GRENADE_DIAGNOSTICS = new HashMap<>();

    private GrenadeIntegration() {}

    public record SupportInfo(boolean supported, String itemId, int count,
                              @Nullable String throwableId, String failureReason) {}

    public record BallisticProfile(String throwableId, double initialSpeed, double gravity,
                                   double airDrag, double waterDrag, double crouchSpeedMultiplier,
                                   int lifetime, boolean shouldBounce, boolean brokeOnGround,
                                   double bounceFactor) {
        public double launchSpeed(boolean crouching) {
            return initialSpeed * (crouching ? crouchSpeedMultiplier : 1.0)
                * StevesArmyConfig.getGrenadeThrowPowerScale();
        }

        public String describe() {
            return String.format("throwableId=%s,speed=%.3f,gravity=%.3f,airDrag=%.3f,waterDrag=%.3f,crouchMultiplier=%.3f,lifetime=%d,shouldBounce=%s,brokeOnGround=%s,bounceFactor=%.3f",
                throwableId, initialSpeed, gravity, airDrag, waterDrag,
                crouchSpeedMultiplier, lifetime, shouldBounce, brokeOnGround, bounceFactor);
        }
    }

    public record BallisticResult(@Nullable BallisticProfile profile, String reason) {
        public boolean available() {
            return profile != null;
        }
    }

    public record ThrowResult(boolean success, int countBefore, int countAfter,
                              @Nullable Vec3 nativeVelocity,
                              @Nullable Vec3 appliedVelocity,
                              boolean velocitySyncBroadcast,
                              @Nullable UUID projectileId,
                              String reason) {
        public double nativeSpeed() {
            return nativeVelocity == null ? 0.0 : nativeVelocity.length();
        }

        public double appliedSpeed() {
            return appliedVelocity == null ? 0.0 : appliedVelocity.length();
        }

        public boolean spreadCorrected() {
            return appliedVelocity != null && nativeVelocity != null;
        }
    }

    private record GrenadeDiagnostic(UUID projectileId, @Nullable UUID targetId,
                                      @Nullable Vec3 targetAtThrow,
                                      @Nullable Vec3 predictedLanding,
                                      @Nullable Vec3 launchOrigin,
                                      @Nullable Vec3 appliedVelocity,
                                      @Nullable String trajectoryDiagnostics,
                                      String mode, int estimatedFlightTicks,
                                      long throwGameTime) {}

    public static String supportedItemDescription() {
        return THROWABLE_ITEM_ID + " with NBT " + THROWABLE_ID_TAG
            + "=lrtactical:m67 or lrtactical:rgn";
    }

    public static BallisticResult inspectBallistics(@Nullable ItemStack stack) {
        SupportInfo support = inspect(stack);
        if (!support.supported()) {
            return new BallisticResult(null, "unsupported throwable: " + support.failureReason());
        }
        try {
            Object throwable = throwableOf.invoke(null, stack);
            Object optional = getThrowableIndex.invoke(throwable, stack);
            if (!(optional instanceof Optional<?> indexOptional) || indexOptional.isEmpty()) {
                return new BallisticResult(null, "LesRaisins returned no ThrowableIndex");
            }

            Object index = indexOptional.get();
            Object data = index.getClass().getMethod("getData").invoke(index);
            Object entityData = data.getClass().getMethod("getEntityData").invoke(data);
            double initialSpeed = number(data, "getInitialSpeed");
            double gravity = number(entityData, "getGravity");
            int lifetime = integer(entityData, "getLifeTime");
            boolean shouldBounce = bool(entityData, "isShouldBounce");
            boolean brokeOnGround = bool(entityData, "isBrokeOnGround");
            double bounceFactor = number(entityData, "getBounceFactor");
            double crouchSpeedMultiplier = readCrouchSpeedMultiplier();
            if (!Double.isFinite(initialSpeed) || initialSpeed <= 0.0
                || !Double.isFinite(gravity) || gravity < 0.0
                || !Double.isFinite(bounceFactor) || bounceFactor < 0.0
                || lifetime <= 0) {
                return new BallisticResult(null, "LesRaisins returned invalid ballistic values");
            }
            return new BallisticResult(new BallisticProfile(
                support.throwableId(), initialSpeed, gravity, 0.99, 0.8,
                crouchSpeedMultiplier, lifetime, shouldBounce, brokeOnGround,
                bounceFactor), "resolved");
        } catch (ReflectiveOperationException | RuntimeException exception) {
            return new BallisticResult(null, "ballistic profile reflection failed: "
                + describeFailure(exception));
        }
    }

    public static void init() {
        if (initialized) return;
        initialized = true;
        if (!ModList.get().isLoaded(MOD_ID)) {
            StevesArmyMod.LOGGER.info("LesRaisins Tactical Equipments not detected - grenade AI disabled");
            return;
        }

        try {
            throwableInterface = Class.forName("me.xjqsh.lrtactical.api.item.IThrowable");
            Class<?> indexClass = Class.forName("me.xjqsh.lrtactical.item.index.ThrowableIndex");
            throwableOf = throwableInterface.getMethod("of", ItemStack.class);
            getId = throwableInterface.getMethod("getId", ItemStack.class);
            getThrowableIndex = throwableInterface.getMethod("getThrowableIndex", ItemStack.class);
            onThrow = Class.forName("me.xjqsh.lrtactical.item.ThrowableItem")
                .getMethod("onThrow", Level.class, LivingEntity.class, ItemStack.class, indexClass);
            available = true;
            StevesArmyMod.LOGGER.info("LesRaisins Tactical Equipments detected - explosive grenade AI enabled");
        } catch (ReflectiveOperationException | LinkageError exception) {
            logFailure(exception);
        }
    }

    public static boolean isAvailable() {
        init();
        return available && StevesArmyConfig.areGrenadesEnabled();
    }

    public static boolean isSupported(ItemStack stack) {
        return inspect(stack).supported();
    }

    public static SupportInfo inspect(@Nullable ItemStack stack) {
        String itemId = itemId(stack);
        int count = stack == null ? 0 : stack.getCount();
        if (stack == null || stack.isEmpty()) {
            return new SupportInfo(false, itemId, count, null, "empty stack");
        }
        if (!isAvailable()) {
            return new SupportInfo(false, itemId, count, null,
                "LesRaisins unavailable or grenades disabled");
        }
        try {
            Object throwable = throwableOf.invoke(null, stack);
            if (throwable == null) {
                String taggedId = readTaggedThrowableId(stack);
                if (taggedId != null) {
                    boolean supported = SUPPORTED_IDS.contains(taggedId);
                    return new SupportInfo(supported, itemId, count, taggedId,
                        supported ? "supported via ThrowableId NBT fallback"
                            : "ThrowableId is not an explosive grenade");
                }
                return new SupportInfo(false, itemId, count, null,
                    "LesRaisins returned no throwable handler");
            }
            Object id = getId.invoke(throwable, stack);
            String throwableId = id == null ? null : id.toString();
            if (throwableId == null) {
                return new SupportInfo(false, itemId, count, null,
                    "LesRaisins returned no throwable ID");
            }
            boolean supported = SUPPORTED_IDS.contains(throwableId);
            return new SupportInfo(supported, itemId, count, throwableId,
                supported ? "supported" : "throwable ID is not an explosive grenade");
        } catch (ReflectiveOperationException | RuntimeException exception) {
            return new SupportInfo(false, itemId, count, null, describeFailure(exception));
        }
    }

    /** Resolves only the general inventory slots reserved for autonomous grenades. */
    public static int findSupportedSlot(SoldierInventory inventory) {
        if (inventory == null) return -1;
        for (int slot = SoldierInventory.SLOT_GENERAL_START;
             slot < inventory.getContainerSize(); slot++) {
            if (inspect(inventory.getItem(slot)).supported()) return slot;
        }
        return -1;
    }

    public static String describeInventory(SoldierInventory inventory) {
        if (inventory == null) return "inventory=null";
        StringBuilder result = new StringBuilder("inventory=[");
        boolean first = true;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack == null || stack.isEmpty()) continue;
            if (!first) result.append(';');
            first = false;
            SupportInfo info = inspect(stack);
            result.append("slot=").append(slot)
                .append(" item=").append(info.itemId())
                .append(" count=").append(info.count())
                .append(" supported=").append(info.supported());
            if (info.throwableId() != null) {
                result.append(" throwableId=").append(info.throwableId());
            }
            result.append(" reason=").append(info.failureReason());
        }
        return result.append(']').toString();
    }

    /** Uses LesRaisins' own entity factory, item consumption, and cooldown path. */
    public static boolean throwGrenade(LivingEntity owner, ItemStack stack) {
        return throwGrenadeDetailed(owner, stack, null, null).success();
    }

    /**
     * Performs the native LesRaisins throw, then replaces its random launch
     * spread with the already validated AI velocity before the next tick.
     */
    public static ThrowResult throwGrenadeDetailed(LivingEntity owner, ItemStack stack,
                                                    @Nullable Vec3 appliedVelocity) {
        return throwGrenadeDetailed(owner, stack, null, appliedVelocity);
    }

    /** Native throw with optional AI-controlled spawn origin and velocity. */
    public static ThrowResult throwGrenadeDetailed(LivingEntity owner, ItemStack stack,
                                                    @Nullable Vec3 launchOrigin,
                                                    @Nullable Vec3 appliedVelocity) {
        int before = stack == null ? 0 : stack.getCount();
        if (!isSupported(stack) || owner == null || owner.level().isClientSide) {
            return new ThrowResult(false, before, before, null, appliedVelocity, false,
                null,
                "unsupported stack, missing owner, or client-side throw");
        }
        Set<java.util.UUID> existingProjectiles = ownedGrenades(owner);
        try {
            Object throwable = throwableOf.invoke(null, stack);
            Object optional = getThrowableIndex.invoke(throwable, stack);
            if (!(optional instanceof Optional<?> indexOptional) || indexOptional.isEmpty()) {
                return new ThrowResult(false, before, stack.getCount(), null, appliedVelocity, false,
                    null,
                    "LesRaisins returned no ThrowableIndex");
            }
            onThrow.invoke(throwable, owner.level(), owner, stack, indexOptional.get());
            Projectile projectile = findNewGrenade(owner, existingProjectiles);
            if (projectile == null) {
                stack.setCount(before);
                return new ThrowResult(false, before, stack.getCount(), null, appliedVelocity, false,
                    null,
                    "native projectile not found after LesRaisins accepted the throw");
            }
            Vec3 nativeVelocity = projectile.getDeltaMovement();
            int after = stack.getCount();
            if (after >= before) {
                return new ThrowResult(false, before, after, nativeVelocity, appliedVelocity, false,
                    projectile.getUUID(),
                    "item consumption mismatch");
            }

            boolean velocitySyncBroadcast = false;
            if (appliedVelocity != null) {
                if (launchOrigin != null) {
                    projectile.setPos(launchOrigin.x, launchOrigin.y, launchOrigin.z);
                }
                projectile.setDeltaMovement(appliedVelocity);
                if (owner.level() instanceof ServerLevel serverLevel) {
                    if (launchOrigin != null) {
                        serverLevel.getChunkSource().broadcastAndSend(projectile,
                            new ClientboundTeleportEntityPacket(projectile));
                    }
                    serverLevel.getChunkSource().broadcastAndSend(projectile,
                        new ClientboundSetEntityMotionPacket(projectile));
                    velocitySyncBroadcast = true;
                } else {
                    return new ThrowResult(false, before, after, nativeVelocity, appliedVelocity, false,
                        projectile.getUUID(),
                        "server level unavailable for corrected velocity synchronization");
                }
            }
            return new ThrowResult(true, before, after, nativeVelocity, appliedVelocity, velocitySyncBroadcast,
                projectile.getUUID(),
                appliedVelocity == null ? "native throw succeeded" : "native spread corrected");
        } catch (ReflectiveOperationException | RuntimeException exception) {
            logFailure(exception);
            return new ThrowResult(false, before, stack.getCount(), null, appliedVelocity, false,
                null,
                "native throw reflection failed: " + describeFailure(exception));
        }
    }

    /** Associates a throw with its target and prediction for one later explosion log. */
    public static void recordDiagnostic(ThrowResult result, @Nullable LivingEntity target,
                                         @Nullable Vec3 targetPoint, @Nullable Vec3 predictedLanding,
                                         @Nullable Vec3 launchOrigin, int estimatedFlightTicks,
                                         String mode, @Nullable String trajectoryDiagnostics,
                                         long gameTime) {
        if (!DiagnosticLogManager.isGrenadeLoggingEnabled() || !result.success()
            || result.projectileId() == null) return;
        GRENADE_DIAGNOSTICS.entrySet().removeIf(entry -> entry.getValue().throwGameTime() + 200 < gameTime);
        Vec3 targetAtThrow = targetPoint != null
            ? targetPoint : target == null ? null : target.position();
        GRENADE_DIAGNOSTICS.put(result.projectileId(), new GrenadeDiagnostic(
            result.projectileId(), target == null ? null : target.getUUID(), targetAtThrow,
            predictedLanding, launchOrigin, result.appliedVelocity(), trajectoryDiagnostics, mode,
            estimatedFlightTicks, gameTime));
        StevesArmyMod.LOGGER.info(
            "[GrenadeTrace] throw projectile={} mode={} target={} targetAtThrow={} origin={} velocity={} predictedLanding={} trajectory={} estimatedFlightTicks={} gameTime={}",
            result.projectileId(), mode, target == null ? "none" : target.getUUID(), targetAtThrow,
            launchOrigin, result.appliedVelocity(), predictedLanding, trajectoryDiagnostics,
            estimatedFlightTicks, gameTime);
    }

    /** Logs the actual native explosion against the correlated throw prediction. */
    public static void logExplosionDiagnostic(Explosion explosion) {
        if (!DiagnosticLogManager.isGrenadeLoggingEnabled()) return;
        Entity exploder = explosion.getExploder();
        if (!isGrenadeEntity(exploder)) return;
        GrenadeDiagnostic diagnostic = GRENADE_DIAGNOSTICS.remove(exploder.getUUID());
        if (diagnostic == null) {
            StevesArmyMod.LOGGER.info(
                "[GrenadeTrace] explode projectile={} mode=untracked actualExplosion={} projectilePosition={} gameTime={}",
                exploder.getUUID(), explosion.getPosition(), exploder.position(),
                exploder.level().getGameTime());
            return;
        }
        Entity target = diagnostic.targetId() == null ? null
            : exploder.level() instanceof ServerLevel serverLevel
                ? serverLevel.getEntity(diagnostic.targetId()) : null;
        Vec3 targetAtDetonation = target instanceof LivingEntity living
            ? new Vec3(living.getX(), living.getEyeY(), living.getZ()) : null;
        double predictionError = diagnostic.predictedLanding() == null
            ? Double.NaN : diagnostic.predictedLanding().distanceTo(explosion.getPosition());
        StevesArmyMod.LOGGER.info(
            "[GrenadeTrace] explode projectile={} mode={} actualExplosion={} projectilePosition={} targetAtThrow={} targetAtDetonation={} predictedLanding={} predictionError={} launchOrigin={} velocity={} trajectory={} ageTicks={} gameTime={}",
            diagnostic.projectileId(), diagnostic.mode(), explosion.getPosition(), exploder.position(),
            diagnostic.targetAtThrow(), targetAtDetonation, diagnostic.predictedLanding(), predictionError,
            diagnostic.launchOrigin(), diagnostic.appliedVelocity(), diagnostic.trajectoryDiagnostics(),
            exploder.level().getGameTime() - diagnostic.throwGameTime(), exploder.level().getGameTime());
    }

    private static Set<java.util.UUID> ownedGrenades(LivingEntity owner) {
        Set<java.util.UUID> result = new HashSet<>();
        for (Projectile projectile : owner.level().getEntitiesOfClass(Projectile.class,
            owner.getBoundingBox().inflate(3.0), candidate -> candidate.getOwner() == owner
                && isGrenadeEntity(candidate))) {
            result.add(projectile.getUUID());
        }
        return result;
    }

    @Nullable
    private static Projectile findNewGrenade(LivingEntity owner, Set<java.util.UUID> existing) {
        AABB searchArea = owner.getBoundingBox().inflate(3.0);
        for (Projectile projectile : owner.level().getEntitiesOfClass(Projectile.class, searchArea,
            candidate -> candidate.getOwner() == owner && isGrenadeEntity(candidate)
                && !existing.contains(candidate.getUUID()))) {
            return projectile;
        }
        return null;
    }

    private static double number(Object target, String method) throws ReflectiveOperationException {
        return ((Number) target.getClass().getMethod(method).invoke(target)).doubleValue();
    }

    private static int integer(Object target, String method) throws ReflectiveOperationException {
        return ((Number) target.getClass().getMethod(method).invoke(target)).intValue();
    }

    private static boolean bool(Object target, String method) throws ReflectiveOperationException {
        return (Boolean) target.getClass().getMethod(method).invoke(target);
    }

    private static double readCrouchSpeedMultiplier() throws ReflectiveOperationException {
        try {
            Class<?> config = Class.forName("me.xjqsh.lrtactical.config.ServerConfig");
            Object value = config.getField("CROUCHING_INIT_SPEED_PERCENT").get(null);
            return ((Number) value.getClass().getMethod("get").invoke(value)).doubleValue();
        } catch (ClassNotFoundException | NoSuchFieldException exception) {
            return 1.0;
        }
    }

    public static boolean isGrenadeEntity(@Nullable Entity entity) {
        init();
        return available && entity != null
            && entity.getClass().getName().equals("me.xjqsh.lrtactical.entity.GrenadeEntity");
    }

    @Nullable
    public static LivingEntity getOwner(@Nullable Entity entity) {
        if (!(entity instanceof Projectile projectile)) return null;
        return projectile.getOwner() instanceof LivingEntity owner ? owner : null;
    }

    private static void logFailure(Throwable exception) {
        if (failureLogged) return;
        failureLogged = true;
        StevesArmyMod.LOGGER.warn("LesRaisins grenade integration unavailable; grenade AI disabled", exception);
    }

    private static String itemId(@Nullable ItemStack stack) {
        if (stack == null || stack.isEmpty()) return "minecraft:air";
        return String.valueOf(BuiltInRegistries.ITEM.getKey(stack.getItem()));
    }

    @Nullable
    private static String readTaggedThrowableId(ItemStack stack) {
        if (!THROWABLE_ITEM_ID.equals(itemId(stack))) return null;
        CompoundTag tag = stack.getTag();
        if (tag == null || !tag.contains(THROWABLE_ID_TAG, Tag.TAG_STRING)) return null;
        ResourceLocation id = ResourceLocation.tryParse(tag.getString(THROWABLE_ID_TAG));
        return id == null ? null : id.toString();
    }

    private static String describeFailure(Exception exception) {
        String message = exception.getMessage();
        return exception.getClass().getSimpleName()
            + (message == null || message.isBlank() ? "" : ":" + message);
    }
}
