package com.stevesarmy.mixins;

import com.stevesarmy.StevesArmyConfig;
import com.stevesarmy.combat.SoldierTrackingRefresh;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Refresh stationary soldiers too when config or the server view distance changes. */
@Mixin(ChunkMap.class)
public abstract class SoldierTrackingRefreshMixin {
    @Shadow(remap = false) @Final private Int2ObjectMap<?> f_140150_;
    @Shadow(remap = false) @Final private ServerLevel f_140133_;
    @Unique private int stevesArmy$lastRange = -1;

    @Inject(method = "m_140421_()V", at = @At("TAIL"), remap = false)
    private void stevesArmy$refreshChangedRange(CallbackInfo ci) {
        int range = StevesArmyConfig.SOLDIER_CHUNK_LOADING_ENABLED.get()
            ? (StevesArmyConfig.SOLDIER_CHUNK_LOADING_DISTANCE.get() + 15) / 16 : 10;
        range = Math.min(range, f_140133_.getServer().getPlayerList().getViewDistance());
        if (range == stevesArmy$lastRange) return;
        stevesArmy$lastRange = range;
        for (Object tracked : f_140150_.values()) {
            if (tracked instanceof SoldierTrackingRefresh refresh) {
                refresh.stevesArmy$refreshSoldierTracking(f_140133_.players());
            }
        }
    }
}
