package com.stevesarmy.compat.ysm;

import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.entity.SoldierEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.loading.FMLEnvironment;

import javax.annotation.Nullable;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/**
 * Optional YSM (Yes Steve Model) integration. Requires the OpenYSM build to expose the API.
 * The mod is optional: all access is gated behind {@link #isLoaded()} so the game runs
 * normally when YSM is absent.
 */
public final class YsmCompat {
    private static final String YSM_MOD_ID = "yes_steve_model";

    private YsmCompat() {
    }

    public static boolean isLoaded() {
        return ModList.get().isLoaded(YSM_MOD_ID);
    }

    /** Called from common setup. Registers the client-only capability attach handler. */
    public static void init() {
        if (isLoaded() && FMLEnvironment.dist.isClient()) {
            try {
                Class<?> eventClass = Class.forName("com.stevesarmy.compat.ysm.SoldierModelCapabilityEvent");
                MinecraftForge.EVENT_BUS.register(eventClass.getDeclaredConstructor().newInstance());
                StevesArmyMod.LOGGER.info("[YSM] Soldier model compatibility enabled");
            } catch (Throwable throwable) {
                StevesArmyMod.LOGGER.warn("[YSM] Failed to initialize optional compatibility: {}", throwable.toString());
            }
        }
    }

    /**
     * Access rule for restyling a soldier: the owning player may restyle their own soldiers,
     * creative players may restyle any soldier.
     */
    public static boolean canEditModel(Player player, SoldierEntity soldier) {
        return soldier.isOwnedBy(player) || player.getAbilities().instabuild;
    }

    @OnlyIn(Dist.CLIENT)
    public static void openModelScreen(SoldierEntity soldier) {
        if (!isLoaded()) {
            return;
        }
        try {
            Class<?> screenClass = Class.forName("com.stevesarmy.compat.ysm.SoldierModelScreen");
            Constructor<?> constructor = screenClass.getConstructor(SoldierEntity.class);
            Minecraft.getInstance().setScreen((net.minecraft.client.gui.screens.Screen) constructor.newInstance(soldier));
        } catch (Throwable throwable) {
            StevesArmyMod.LOGGER.warn("[YSM] Failed to open soldier model screen: {}", throwable.toString());
        }
    }

    @OnlyIn(Dist.CLIENT)
    public static void disableModel(SoldierEntity soldier) {
        if (isLoaded()) {
            try {
                Class<?> providerClass = Class.forName("com.stevesarmy.compat.ysm.SoldierModelCapabilityProvider");
                Method getMethod = providerClass.getMethod("get", SoldierEntity.class);
                Object capability = getMethod.invoke(null, soldier);
                if (capability != null) {
                    capability.getClass().getMethod("resetModel").invoke(capability);
                }
            } catch (Throwable throwable) {
                StevesArmyMod.LOGGER.warn("[YSM] Failed to reset soldier model: {}", throwable.toString());
            }
        }
        soldier.setYsmModelId("");
        soldier.setYsmTextureId("");
    }

    @OnlyIn(Dist.CLIENT)
    public static void requestDisableModel(SoldierEntity soldier) {
        if (!isLoaded()) {
            return;
        }
        try {
            Class<?> packetClass = Class.forName("com.stevesarmy.compat.ysm.C2SRequestSoldierModelPacket");
            Object packet = packetClass.getMethod("clear", int.class).invoke(null, soldier.getId());
            Class<?> networkClass = Class.forName("com.stevesarmy.network.NetworkHandler");
            Object channel = networkClass.getField("INSTANCE").get(null);
            channel.getClass().getMethod("sendToServer", Object.class).invoke(channel, packet);
        } catch (Throwable throwable) {
            StevesArmyMod.LOGGER.warn("[YSM] Failed to request soldier model reset: {}", throwable.toString());
        }
    }

    /**
     * Creates the geo renderer used as a delegate by the vanilla soldier renderers.
     * Returns null when YSM is unavailable or the renderer cannot be built.
     */
    @OnlyIn(Dist.CLIENT)
    @Nullable
    public static ISoldierGeoRenderer createGeoRenderer(EntityRendererProvider.Context context) {
        if (!isLoaded()) {
            return null;
        }
        try {
            Class<?> rendererClass = Class.forName("com.stevesarmy.compat.ysm.SoldierModelRenderer");
            return (ISoldierGeoRenderer) rendererClass
                .getConstructor(EntityRendererProvider.Context.class)
                .newInstance(context);
        } catch (Throwable throwable) {
            StevesArmyMod.LOGGER.warn("[YSM] Failed to create soldier geo renderer: {}", throwable.toString());
            return null;
        }
    }
}
