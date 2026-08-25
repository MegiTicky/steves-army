package com.stevesarmy.compat.ysm;

import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.entity.SoldierEntity;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.event.AttachCapabilitiesEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/** Attaches the client-only model capability to soldier entities (including enemy soldiers). */
@OnlyIn(Dist.CLIENT)
public final class SoldierModelCapabilityEvent {

    private static final ResourceLocation CAPABILITY_KEY =
        new ResourceLocation(StevesArmyMod.MODID, "soldier_model");

    @SubscribeEvent
    public void onAttachCapabilities(AttachCapabilitiesEvent<Entity> event) {
        if (event.getObject() instanceof SoldierEntity soldier && soldier.level().isClientSide) {
            event.addCapability(CAPABILITY_KEY, new SoldierModelCapabilityProvider(soldier));
        }
    }
}
