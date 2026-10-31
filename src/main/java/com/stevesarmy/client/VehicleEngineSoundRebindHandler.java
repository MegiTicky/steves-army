package com.stevesarmy.client;

import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.compat.sbw.SbwCompat;
import com.stevesarmy.compat.sbw.SbwReflection;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.sound.PlaySoundEvent;
import net.minecraftforge.event.entity.EntityMountEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/** Restarts SBW's own engine loop when its edge-trigger state outlives its sound instance. */
@Mod.EventBusSubscriber(modid = StevesArmyMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class VehicleEngineSoundRebindHandler {
    private static final String ENGINE_SOUND_CLASS =
        "com.atsuishio.superbwarfare.client.sound.VehicleSoundInstance$EngineSound";
    private static final Map<UUID, WeakReference<SoundInstance>> ENGINE_SOUNDS = new HashMap<>();

    private VehicleEngineSoundRebindHandler() {}

    @SubscribeEvent
    public static void onPlaySound(PlaySoundEvent event) {
        SoundInstance sound = event.getSound();
        if (sound == null || !ENGINE_SOUND_CLASS.equals(sound.getClass().getName())) return;
        Object vehicle = SbwReflection.get(sound, "mobileVehicle");
        if (vehicle instanceof Entity entity) {
            ENGINE_SOUNDS.put(entity.getUUID(), new WeakReference<>(sound));
        }
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Iterator<Map.Entry<UUID, WeakReference<SoundInstance>>> it = ENGINE_SOUNDS.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, WeakReference<SoundInstance>> entry = it.next();
            if (entry.getValue().get() == null) it.remove();
        }
    }

    @SubscribeEvent
    public static void onMount(EntityMountEvent event) {
        if (!event.isMounting() || !(event.getEntityMounting() instanceof Player rider)
            || rider != Minecraft.getInstance().player) return;
        Entity vehicle = event.getEntityBeingMounted();
        if (!SbwCompat.isVehicle(vehicle)) return;

        SoundInstance previous = null;
        WeakReference<SoundInstance> reference = ENGINE_SOUNDS.get(vehicle.getUUID());
        if (reference != null) previous = reference.get();
        Object soundManager = Minecraft.getInstance().getSoundManager();
        boolean active = previous != null && SbwReflection.asBool(
            SbwReflection.call(soundManager, new String[]{"isActive"}, previous), false);
        boolean dying = previous != null && SbwReflection.asBool(
            SbwReflection.get(previous, "die"), false);
        if (active && !dying) return;

        if (active) {
            SbwReflection.invoke(soundManager, new String[]{"stop"}, previous);
        }
        // SBW starts its engine loop on a false -> true engine-running edge. The
        // loop can already have died after an AI hand-off while this cached bit
        // remained true; reset only that edge so SBW recreates its native sound.
        SbwReflection.set(vehicle, false, "wasEngineRunning");
    }
}
