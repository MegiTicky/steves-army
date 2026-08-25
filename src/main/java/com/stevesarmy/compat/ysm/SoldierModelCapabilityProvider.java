package com.stevesarmy.compat.ysm;

import com.stevesarmy.entity.SoldierEntity;
import net.minecraft.core.Direction;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.CapabilityManager;
import net.minecraftforge.common.capabilities.CapabilityToken;
import net.minecraftforge.common.capabilities.ICapabilityProvider;
import net.minecraftforge.common.util.LazyOptional;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

@OnlyIn(Dist.CLIENT)
public class SoldierModelCapabilityProvider implements ICapabilityProvider {

    public static final Capability<SoldierModelCapability> SOLDIER_MODEL_CAP =
        CapabilityManager.get(new CapabilityToken<SoldierModelCapability>() {
        });

    private SoldierModelCapability capability;
    private final SoldierEntity soldier;

    public SoldierModelCapabilityProvider(SoldierEntity soldier) {
        this.soldier = soldier;
    }

    @NotNull
    @Override
    public <T> LazyOptional<T> getCapability(@NotNull Capability<T> capability, @Nullable Direction direction) {
        return getCapability(capability);
    }

    @NotNull
    @Override
    public <T> LazyOptional<T> getCapability(@NotNull Capability<T> capability) {
        return SOLDIER_MODEL_CAP.orEmpty(capability, LazyOptional.of(this::getOrCreateCapability));
    }

    /** Returns the soldier's model capability, or null when it has not been attached. */
    @Nullable
    public static SoldierModelCapability get(SoldierEntity soldier) {
        return soldier.getCapability(SOLDIER_MODEL_CAP).orElse(null);
    }

    private SoldierModelCapability getOrCreateCapability() {
        if (this.capability == null) {
            this.capability = new SoldierModelCapability(this.soldier, true);
        }
        return this.capability;
    }
}
