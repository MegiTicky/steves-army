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
            MinecraftForge.EVENT_BUS.register(new SoldierModelCapabilityEvent());
            StevesArmyMod.LOGGER.info("[YSM] Soldier model compatibility enabled");
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
        Minecraft.getInstance().setScreen(new SoldierModelScreen(soldier));
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
            return new SoldierModelRenderer(context);
        } catch (Throwable throwable) {
            StevesArmyMod.LOGGER.warn("[YSM] Failed to create soldier geo renderer: {}", throwable.toString());
            return null;
        }
    }
}
