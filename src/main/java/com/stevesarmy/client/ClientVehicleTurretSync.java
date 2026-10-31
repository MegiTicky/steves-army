package com.stevesarmy.client;

import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.compat.sbw.SbwCompat;
import com.stevesarmy.compat.sbw.SbwReflection;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/** Applies server station angles once per client tick, after SBW's own vehicle tick. */
@Mod.EventBusSubscriber(modid = StevesArmyMod.MODID, value = Dist.CLIENT)
public final class ClientVehicleTurretSync {
    private record Key(UUID vehicle, boolean turret) {}
    private static final class Sample {
        int entityId;
        float yaw, pitch;
        long receivedTick;
        boolean dirty;
    }
    private static final Map<Key, Sample> SAMPLES = new HashMap<>();
    private static long tick;
    private ClientVehicleTurretSync() {}

    public static void receive(int entityId, UUID uuid, boolean turret, float yaw, float pitch) {
        if (!Float.isFinite(yaw) || !Float.isFinite(pitch)) return;
        Key key = new Key(uuid, turret);
        Sample sample = SAMPLES.computeIfAbsent(key, ignored -> new Sample());
        sample.entityId = entityId;
        sample.yaw = yaw;
        sample.pitch = pitch;
        sample.receivedTick = tick;
        sample.dirty = true;
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        tick++;
        var level = Minecraft.getInstance().level;
        if (level == null) { SAMPLES.clear(); return; }
        Iterator<Map.Entry<Key, Sample>> it = SAMPLES.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Key, Sample> entry = it.next();
            Sample sample = entry.getValue();
            Entity vehicle = level.getEntity(sample.entityId);
            if (tick - sample.receivedTick > 10 || vehicle == null
                || !entry.getKey().vehicle().equals(vehicle.getUUID()) || !SbwCompat.isVehicle(vehicle)) {
                it.remove();
                continue;
            }
            // No fresh server sample: do not interfere with SBW or a new player
            // controller. SBW's own tick advances the previous-angle fields.
            if (!sample.dirty) continue;
            boolean turret = entry.getKey().turret();
            String yawName = turret ? "turretYRot" : "gunYRot";
            String pitchName = turret ? "turretXRot" : "gunXRot";
            Object y = SbwReflection.get(vehicle, yawName, turret ? "getTurretYRot" : "getGunYRot");
            Object x = SbwReflection.get(vehicle, pitchName, turret ? "getTurretXRot" : "getGunXRot");
            if (!(y instanceof Number oldY) || !(x instanceof Number oldX)) continue;
            float yaw = sample.yaw;
            float pitch = sample.pitch;
            float dy = Mth.wrapDegrees(yaw - oldY.floatValue());
            float dx = pitch - oldX.floatValue();
            // Keep previous angles until the next client tick, for render interpolation.
            // Unwrap yaw across SBW's +/-360 reset to avoid a full revolution flicker.
            SbwReflection.set(vehicle, yaw - dy, yawName + "O");
            SbwReflection.set(vehicle, oldX.floatValue(), pitchName + "O");
            SbwReflection.set(vehicle, yaw, yawName);
            SbwReflection.set(vehicle, pitch, pitchName);
            // This native SBW method only plays on the client, and applies its
            // normal threshold, volume, sound event and random pitch.
            String type = com.stevesarmy.compat.SbwCompat.getVehicleTypeName(vehicle);
            if (turret && ("TANK".equals(type) || "APC".equals(type) || "AA".equals(type))) {
                SbwReflection.invoke(vehicle, new String[]{"turretTurnSound"}, dx, dy, 0.95f);
            }
            sample.dirty = false;
        }
    }
}
