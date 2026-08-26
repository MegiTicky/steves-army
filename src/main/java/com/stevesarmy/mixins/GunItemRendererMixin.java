package com.stevesarmy.mixins;

import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.client.SoldierRenderTracker;
import com.stevesarmy.entity.SoldierEntity;
import com.vicmatskiv.pointblank.client.ClientEventHandler;
import com.vicmatskiv.pointblank.client.render.GunItemRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemDisplayContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Vic's Point Blank refuses to draw guns held by non-Player entities in third
 * person: GunItemRenderer.getPlayer() only returns the client player (first
 * person / ground) or the currently rendered entity when it is a Player, and
 * otherwise returns null, which makes renderByItem() bail out. Soldiers are
 * LivingEntities, so their VPB guns were invisible.
 *
 * <p>When one of our soldiers is being rendered, stand the client player in for
 * the state lookup. The soldier's gun stack is not in the player inventory, so
 * GunClientState.getState falls through to the no-slot map keyed by the stack
 * UUID and simply returns a static state; the gun model is drawn without
 * player-bound animations.
 */
@Mixin(value = GunItemRenderer.class, remap = false)
public class GunItemRendererMixin {

    static {
        StevesArmyMod.LOGGER.info("[GunItemRendererMixin] loaded and registered on GunItemRenderer");
    }

    @Inject(method = "getPlayer", at = @At("HEAD"), cancellable = true, require = 0)
    private void stevesarmy$renderVpbGunsOnSoldiers(ItemDisplayContext itemDisplayContext,
                                                    CallbackInfoReturnable<Player> cir) {
        if (itemDisplayContext == ItemDisplayContext.FIRST_PERSON_LEFT_HAND
            || itemDisplayContext == ItemDisplayContext.FIRST_PERSON_RIGHT_HAND
            || itemDisplayContext == ItemDisplayContext.GROUND) {
            return;
        }
        LivingEntity trackerEntity = SoldierRenderTracker.current();
        LivingEntity vpbEntity = null;
        try {
            vpbEntity = ClientEventHandler.getCurrentEntityLiving();
        } catch (Throwable ignored) {
        }
        if (!(trackerEntity instanceof SoldierEntity) && !(vpbEntity instanceof SoldierEntity)) {
            return;
        }
        LocalPlayer clientPlayer = Minecraft.getInstance().player;
        if (clientPlayer != null) {
            StevesArmyMod.LOGGER.info("[VPB] standing client player in for soldier gun render ctx={} tracker={} vpb={}",
                itemDisplayContext, trackerEntity, vpbEntity);
            cir.setReturnValue(clientPlayer);
        }
    }
}
