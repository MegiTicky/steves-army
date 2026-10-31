package com.stevesarmy.combat.throwable;

import com.stevesarmy.StevesArmyConfig;
import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.combat.GrenadeIntegration.BallisticProfile;
import com.stevesarmy.combat.GrenadeIntegration.BallisticResult;
import com.stevesarmy.combat.GrenadeIntegration.Category;
import com.stevesarmy.combat.GrenadeIntegration.SupportInfo;
import com.stevesarmy.combat.GrenadeIntegration.ThrowResult;
import com.stevesarmy.compat.sbw.SbwCompat;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.registries.ForgeRegistries;

import javax.annotation.Nullable;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Superb Warfare throwables integration.
 */
public final class SbwThrowableProvider implements ThrowableProvider {

    private static final String NAMESPACE = "superbwarfare";
    private static final String THROW_SOUND = "grenade_throw";

    private record Spec(String itemId, String entityClass, boolean explosive, boolean smoke,
                        float maxPower, int cookTicks, int useDuration, int cooldownTicks,
                        double gravity, double airDrag, double waterDrag,
                        boolean bounces, double bounceFactor) {

        int fuseTicks() {
            return Math.max(1, useDuration - cookTicks);
        }
    }

    private static final Map<String, Spec> SPECS = new LinkedHashMap<>();

    static {
        put(new Spec(NAMESPACE + ":hand_grenade",
                "com.atsuishio.superbwarfare.entity.projectile.HandGrenadeEntity",
                true, false, 1.5F, 20, 100, 25,
                0.05, 0.98, 0.8, true, 0.5));
        put(new Spec(NAMESPACE + ":rgo_grenade",
                "com.atsuishio.superbwarfare.entity.projectile.RgoGrenadeEntity",
                true, false, 1.8F, 20, 80, 20,
                0.05, 1.0, 0.8, false, 0.0));
        put(new Spec(NAMESPACE + ":m18_smoke_grenade",
                "com.atsuishio.superbwarfare.entity.projectile.M18SmokeGrenadeEntity",
                false, true, 1.8F, 20, 80, 20,
                0.07, 0.98, 0.8, true, 0.5));
    }

    private static void put(Spec spec) {
        SPECS.put(spec.itemId(), spec);
    }

    private static final Map<String, Constructor<?>> CONSTRUCTORS = new LinkedHashMap<>();
    private static final Set<String> GRENADE_ENTITY_CLASSES = Set.of(
            "com.atsuishio.superbwarfare.entity.projectile.HandGrenadeEntity",
            "com.atsuishio.superbwarfare.entity.projectile.RgoGrenadeEntity",
            "com.atsuishio.superbwarfare.entity.projectile.M18SmokeGrenadeEntity");

    private static boolean initialized;
    private static boolean available;
    private static boolean failureLogged;
    private static Method setLife;

    @Override
    public String id() {
        return SbwCompat.MOD_ID;
    }

    @Override
    public boolean isAvailable() {
        init();
        return available && SbwCompat.isLoaded() && StevesArmyConfig.areGrenadesEnabled();
    }

    @Override
    public boolean claims(@Nullable ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        return SPECS.containsKey(itemId(stack));
    }

    @Override
    public SupportInfo inspect(@Nullable ItemStack stack, Category category) {
        String itemId = itemId(stack);
        int count = stack == null ? 0 : stack.getCount();
        if (stack == null || stack.isEmpty()) {
            return new SupportInfo(false, itemId, count, null, "empty stack");
        }
        if (!isAvailable()) {
            return new SupportInfo(false, itemId, count, null,
                    "Superb Warfare unavailable or grenades disabled");
        }
        Spec spec = SPECS.get(itemId);
        if (spec == null) {
            return new SupportInfo(false, itemId, count, null, "not a known Superb Warfare throwable");
        }
        if (!CONSTRUCTORS.containsKey(itemId)) {
            return new SupportInfo(false, itemId, count, itemId,
                    "entity constructor unresolved: " + spec.entityClass());
        }

        boolean matchesCategory = (category == Category.SMOKE && spec.smoke())
            || (category == Category.EXPLOSIVE && spec.explosive());
        if (!matchesCategory) {
            return new SupportInfo(false, itemId, count, itemId,
                category == Category.SMOKE ? "throwable ID is not a smoke grenade" : "throwable ID is not an explosive grenade");
        }

        return new SupportInfo(true, itemId, count, itemId,
                spec.smoke() ? "supported smoke grenade" : "supported");
    }

    @Override
    public BallisticResult inspectBallistics(@Nullable ItemStack stack, Category category) {
        SupportInfo support = inspect(stack, category);
        if (!support.supported()) {
            return new BallisticResult(null, "unsupported throwable: " + support.failureReason());
        }
        Spec spec = SPECS.get(support.throwableId());
        if (spec == null) {
            return new BallisticResult(null, "no ballistic spec for " + support.throwableId());
        }
        return new BallisticResult(new BallisticProfile(
                spec.itemId(), spec.maxPower(), spec.gravity(), spec.airDrag(), spec.waterDrag(),
                1.0, spec.fuseTicks(), spec.bounces(), !spec.bounces(), spec.bounceFactor()),
                "resolved from Superb Warfare entity constants");
    }

    @Override
    public ThrowResult throwGrenade(LivingEntity owner, ItemStack stack,
                                    @Nullable Vec3 launchOrigin,
                                    @Nullable Vec3 appliedVelocity) {
        int before = stack == null ? 0 : stack.getCount();
        Category cat = (stack != null && stack.getItem().getDescriptionId().contains("smoke"))
            ? Category.SMOKE : Category.EXPLOSIVE;
        SupportInfo support = inspect(stack, cat);
        if (!support.supported()) {
            return new ThrowResult(false, before, before, null, appliedVelocity, false, null,
                    "unsupported stack: " + support.failureReason());
        }
        if (owner == null || owner.level().isClientSide) {
            return new ThrowResult(false, before, before, null, appliedVelocity, false, null,
                    "missing owner or client-side throw");
        }
        if (!(owner.level() instanceof ServerLevel serverLevel)) {
            return new ThrowResult(false, before, before, null, appliedVelocity, false, null,
                    "server level unavailable");
        }

        Spec spec = SPECS.get(support.throwableId());
        Constructor<?> constructor = CONSTRUCTORS.get(spec.itemId());
        if (constructor == null) {
            return new ThrowResult(false, before, before, null, appliedVelocity, false, null,
                    "entity constructor unresolved: " + spec.entityClass());
        }

        Projectile projectile;
        try {
            Object raw = spec.smoke()
                    ? constructor.newInstance(owner, serverLevel, spec.fuseTicks())
                    : constructor.newInstance(owner, serverLevel);
            if (!(raw instanceof Projectile created)) {
                return new ThrowResult(false, before, before, null, appliedVelocity, false, null,
                        spec.entityClass() + " is not a Projectile");
            }
            projectile = created;
            if (!spec.smoke()) {
                if (setLife != null) {
                    setLife.invoke(projectile, spec.fuseTicks());
                }
            } else {
                applyDyeColor(stack, projectile);
            }
        } catch (ReflectiveOperationException | RuntimeException exception) {
            logFailure(exception);
            return new ThrowResult(false, before, before, null, appliedVelocity, false, null,
                    "native spawn reflection failed: " + describeFailure(exception));
        }

        Vec3 origin = launchOrigin != null
                ? launchOrigin
                : new Vec3(owner.getX(), owner.getEyeY() - 0.1, owner.getZ());
        projectile.setPos(origin.x, origin.y, origin.z);

        projectile.shootFromRotation(owner, owner.getXRot(), owner.getYRot(), 0.0F, spec.maxPower(), 0.0F);
        Vec3 nativeVelocity = projectile.getDeltaMovement();

        if (appliedVelocity != null) {
            projectile.setDeltaMovement(appliedVelocity);
            faceMotion(projectile, appliedVelocity);
        }
        projectile.setOldPosAndRot();

        if (!serverLevel.addFreshEntity(projectile)) {
            projectile.discard();
            return new ThrowResult(false, before, stack.getCount(), nativeVelocity, appliedVelocity, false, null,
                    "level rejected the projectile");
        }

        boolean velocitySyncBroadcast = false;
        if (appliedVelocity != null) {
            serverLevel.getChunkSource().broadcastAndSend(projectile,
                    new ClientboundSetEntityMotionPacket(projectile));
            velocitySyncBroadcast = true;
        }

        playThrowSound(serverLevel, origin);
        consume(owner, stack, spec);
        int after = stack.getCount();
        String reason = appliedVelocity == null ? "native throw succeeded" : "native spread corrected";
        return new ThrowResult(true, before, after, nativeVelocity, appliedVelocity,
                velocitySyncBroadcast, projectile.getUUID(), reason);
    }

    public static boolean isGrenadeEntity(@Nullable Entity entity) {
        init();
        return available && entity != null
                && GRENADE_ENTITY_CLASSES.contains(entity.getClass().getName());
    }

    public static String supportedItemDescription() {
        return String.join(" or ", SPECS.keySet());
    }

    public static synchronized void init() {
        if (initialized) return;
        initialized = true;
        if (!SbwCompat.isLoaded()) {
            return;
        }

        for (Spec spec : SPECS.values()) {
            try {
                Class<?> entityClass = Class.forName(spec.entityClass());
                Constructor<?> constructor = spec.smoke()
                    ? entityClass.getConstructor(LivingEntity.class, Level.class, int.class)
                    : entityClass.getConstructor(LivingEntity.class, Level.class);

                CONSTRUCTORS.put(spec.itemId(), constructor);

                if (setLife == null && !spec.smoke()) {
                    try {
                        setLife = entityClass.getMethod("setLife", int.class);
                    } catch (ReflectiveOperationException ignored) {}
                }

                available = true;
            } catch (ReflectiveOperationException | LinkageError exception) {
                StevesArmyMod.LOGGER.warn("[SBW] throwable {} unavailable: {}",
                    spec.itemId(), exception.toString());
            }
        }

        if (available) {
            StevesArmyMod.LOGGER.info("[SBW] Superb Warfare throwable integration enabled");
        }
    }

    private static void applyDyeColor(ItemStack stack, Projectile projectile) {
        try {
            Method getColor = stack.getItem().getClass().getMethod("getColor", ItemStack.class);
            int color = ((Number) getColor.invoke(stack.getItem(), stack)).intValue();
            projectile.getClass()
                    .getMethod("setColor", float.class, float.class, float.class)
                    .invoke(projectile,
                            ((color >> 16) & 255) / 255.0F,
                            ((color >> 8) & 255) / 255.0F,
                            (color & 255) / 255.0F);
        } catch (ReflectiveOperationException | RuntimeException ignored) {}
    }

    private static void consume(LivingEntity owner, ItemStack stack, Spec spec) {
        if (owner instanceof Player player) {
            player.getCooldowns().addCooldown(stack.getItem(), spec.cooldownTicks());
            if (player.isCreative()) return;
        }
        stack.shrink(1);
    }

    private static void playThrowSound(ServerLevel level, Vec3 origin) {
        SoundEvent sound = ForgeRegistries.SOUND_EVENTS
                .getValue(new ResourceLocation(NAMESPACE, THROW_SOUND));
        if (sound == null) return;
        level.playSound(null, origin.x, origin.y, origin.z, sound, SoundSource.PLAYERS, 1.0F, 1.0F);
    }

    private static void faceMotion(Projectile projectile, Vec3 motion) {
        float yRot = (float) (-Mth.atan2(motion.x, motion.z) * (180.0 / Math.PI));
        float xRot = (float) (-Mth.atan2(motion.y, motion.horizontalDistance()) * (180.0 / Math.PI));
        projectile.setYRot(yRot);
        projectile.setXRot(xRot);
        projectile.yRotO = yRot;
        projectile.xRotO = xRot;
    }

    private static String itemId(@Nullable ItemStack stack) {
        if (stack == null || stack.isEmpty()) return "minecraft:air";
        return String.valueOf(ForgeRegistries.ITEMS.getKey(stack.getItem()));
    }

    private static void logFailure(Throwable exception) {
        if (failureLogged) return;
        failureLogged = true;
        StevesArmyMod.LOGGER.warn("[SBW] Superb Warfare throwable error: {}", exception.toString());
    }

    private static String describeFailure(Exception exception) {
        String message = exception.getMessage();
        return exception.getClass().getSimpleName()
                + (message == null || message.isBlank() ? "" : ":" + message);
    }
}