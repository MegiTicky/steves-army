package com.stevesarmy.mixins;

import com.tacz.guns.client.resource.GunDisplayInstance;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Mixin(value = GunDisplayInstance.class, remap = false)
public class GunDisplayInstanceMixin {

    private static final Map<ResourceLocation, ResourceLocation> SOUND_CACHE = new ConcurrentHashMap<>();
    private static final ResourceLocation FALLBACK = ResourceLocation.tryParse("steves_army:generic_shoot_3p");

    @Inject(method = "getSounds", at = @At("RETURN"), cancellable = true)
    private void onGetSounds(String name, CallbackInfoReturnable<ResourceLocation> cir) {
        ResourceLocation original = cir.getReturnValue();
        if (original == null) return;
        if (!"shoot_3p".equals(name) && !"silence_3p".equals(name)) return;

        ResourceLocation cached = SOUND_CACHE.get(original);
        if (cached != null) {
            cir.setReturnValue(cached);
            return;
        }

        ResourceLocation filePath = ResourceLocation.tryParse(
            original.getNamespace() + ":tacz_sounds/" + original.getPath() + ".ogg");
        boolean exists = Minecraft.getInstance().getResourceManager()
            .getResource(filePath).isPresent();
        ResourceLocation result = exists ? original : FALLBACK;
        SOUND_CACHE.put(original, result);
        cir.setReturnValue(result);
    }
}