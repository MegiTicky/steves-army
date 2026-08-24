package com.stevesarmy.combat;

import com.stevesarmy.StevesArmyConfig;
import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.inventory.SoldierInventory;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraftforge.fml.ModList;

import javax.annotation.Nullable;
import java.lang.reflect.Method;
import java.util.Optional;
import java.util.Set;

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

    private GrenadeIntegration() {}

    public record SupportInfo(boolean supported, String itemId, int count,
                              @Nullable String throwableId, String failureReason) {}

    public static String supportedItemDescription() {
        return THROWABLE_ITEM_ID + " with NBT " + THROWABLE_ID_TAG
            + "=lrtactical:m67 or lrtactical:rgn";
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
        if (!isSupported(stack) || owner == null || owner.level().isClientSide) return false;
        int before = stack.getCount();
        try {
            Object throwable = throwableOf.invoke(null, stack);
            Object optional = getThrowableIndex.invoke(throwable, stack);
            if (!(optional instanceof Optional<?> indexOptional) || indexOptional.isEmpty()) return false;
            onThrow.invoke(throwable, owner.level(), owner, stack, indexOptional.get());
            return stack.getCount() < before;
        } catch (ReflectiveOperationException | RuntimeException exception) {
            logFailure(exception);
            return false;
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
