package com.stevesarmy.combat;

import com.stevesarmy.StevesArmyMod;
import net.minecraft.world.level.Level;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Invalidates multi-tick combat caches when world geometry or entities change. */
@Mod.EventBusSubscriber(modid = StevesArmyMod.MODID)
public final class CombatCacheInvalidationHandler {
    private CombatCacheInvalidationHandler() {}

    @SubscribeEvent
    public static void onBlockBreak(BlockEvent.BreakEvent event) {
        invalidate(event.getPlayer().level());
    }

    @SubscribeEvent
    public static void onBlockPlace(BlockEvent.EntityPlaceEvent event) {
        invalidate(event.getEntity().level());
    }

    @SubscribeEvent
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (event.getEntity() instanceof LivingEntity) {
            invalidate(event.getLevel());
        }
    }

    @SubscribeEvent
    public static void onLivingDeath(LivingDeathEvent event) {
        invalidate(event.getEntity().level());
    }

    private static void invalidate(Level level) {
        CombatTargetQueryCache.invalidate(level);
        TargetAcquisition.invalidateCaches(level);
        ExposureCalculator.invalidateCaches(level);
        VisibilityRay.invalidateCache(level);
    }
}
