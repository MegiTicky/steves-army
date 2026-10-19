package com.stevesarmy.mixins;

import com.stevesarmy.StevesArmyConfig;
import com.stevesarmy.entity.SoldierEntity;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Extends the vanilla tracking range for soldiers when optional chunk loading is enabled. */
@Mixin(targets = "net.minecraft.server.level.ChunkMap$TrackedEntity")
public abstract class SoldierTrackingRangeMixin implements com.stevesarmy.combat.SoldierTrackingRefresh {
    @org.spongepowered.asm.mixin.Shadow(remap = false)
    @org.spongepowered.asm.mixin.Final
    Entity f_140472_;

    @org.spongepowered.asm.mixin.Shadow(remap = false)
    public abstract void m_140487_(java.util.List<net.minecraft.server.level.ServerPlayer> players);

    @Override
    public void stevesArmy$refreshSoldierTracking(java.util.List<net.minecraft.server.level.ServerPlayer> players) {
        if (f_140472_ instanceof SoldierEntity) m_140487_(players);
    }

    @Inject(method = "m_140496_()I", at = @At("RETURN"), cancellable = true, remap = false)
    private void stevesArmy$extendSoldierTrackingRange(CallbackInfoReturnable<Integer> cir) {
        if (!(f_140472_ instanceof SoldierEntity)) return;
        if (!StevesArmyConfig.SOLDIER_CHUNK_LOADING_ENABLED.get()) return;

        int configuredBlocks = StevesArmyConfig.SOLDIER_CHUNK_LOADING_DISTANCE.get();
        int configuredRange = ((configuredBlocks + 15) / 16) * 16;
        cir.setReturnValue(configuredRange);
    }

}
