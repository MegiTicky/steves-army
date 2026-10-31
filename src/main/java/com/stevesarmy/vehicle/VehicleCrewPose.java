package com.stevesarmy.vehicle;

import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.compat.sbw.SbwCompat;
import com.stevesarmy.compat.sbw.SbwVehicles;
import com.stevesarmy.entity.SoldierEntity;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityMountEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.lang.ref.WeakReference;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Keeps seated soldiers' body yaw on the hull and writes their head aim directly. */
@Mod.EventBusSubscriber(modid = StevesArmyMod.MODID)
public final class VehicleCrewPose {
    private static final float HEAD_ARC = 70.0f;
    private static final float ENCLOSED_HEAD_ARC = 40.0f;
    private static final float PITCH_ARC = 35.0f;
    private static final int IDLE_GLANCE_MIN = 30;
    private static final int IDLE_GLANCE_MAX = 145;

    private static final Map<UUID, Pose> POSES = new ConcurrentHashMap<>();
    private static final Map<UUID, WeakReference<SoldierEntity>> MOUNTED = new ConcurrentHashMap<>();

    private VehicleCrewPose() {}

    private static final class Pose {
        float glanceYaw;
        float glancePitch;
        int side;
        long nextGlanceAt;
    }

    @SubscribeEvent
    public static void onMount(EntityMountEvent event) {
        if (!(event.getEntityMounting() instanceof SoldierEntity soldier) || soldier.level().isClientSide) return;
        UUID id = soldier.getUUID();
        if (event.isMounting() && SbwCompat.isVehicle(event.getEntityBeingMounted())) {
            MOUNTED.put(id, new WeakReference<>(soldier));
        } else {
            MOUNTED.remove(id);
            POSES.remove(id);
        }
    }

    /** Mount events are not replayed when a chunk loads with passengers already seated. */
    public static void ensureTracked(SoldierEntity soldier) {
        if (soldier != null && !soldier.level().isClientSide) {
            MOUNTED.putIfAbsent(soldier.getUUID(), new WeakReference<>(soldier));
        }
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !SbwCompat.hasVehicles()) return;
        Set<UUID> handled = new HashSet<>();

        for (VehicleDriver driver : VehicleDriverRegistry.all()) {
            Entity vehicle = driver.vehicle();
            if (vehicle == null || vehicle.isRemoved()) continue;
            for (Entity passenger : vehicle.getPassengers()) {
                if (passenger instanceof SoldierEntity soldier && handled.add(soldier.getUUID())) {
                    apply(soldier, vehicle);
                }
            }
        }

        Iterator<Map.Entry<UUID, WeakReference<SoldierEntity>>> it = MOUNTED.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, WeakReference<SoldierEntity>> entry = it.next();
            SoldierEntity soldier = entry.getValue().get();
            if (soldier == null || !soldier.isAlive() || soldier.level().isClientSide) {
                it.remove();
                POSES.remove(entry.getKey());
                continue;
            }
            Entity vehicle = soldier.getVehicle();
            if (!SbwCompat.isVehicle(vehicle) || SbwVehicles.isDrone(vehicle)) {
                it.remove();
                POSES.remove(entry.getKey());
                continue;
            }
            if (handled.add(soldier.getUUID())) apply(soldier, vehicle);
        }
        POSES.keySet().removeIf(id -> !handled.contains(id));
    }

    private static void apply(SoldierEntity soldier, Entity vehicle) {
        float hullYaw = vehicle.getYRot();
        // Wheelchair physics derives its heading from the driver's head yaw.
        // Do not let the normal idle glance pull the chair into a spin.
        if (SbwVehicles.kind(vehicle) == SbwVehicles.Kind.WHEELCHAIR
            && SbwVehicles.isDriver(vehicle, soldier)) {
            VehicleDriver driver = VehicleDriverRegistry.get(vehicle);
            if (driver != null && driver.driverSoldier() == soldier) {
                Float heading = driver.wheelchairHeading();
                writePose(soldier, vehicle, heading == null ? hullYaw : heading, 0.0f);
                return;
            }
        }
        Pose pose = POSES.computeIfAbsent(soldier.getUUID(), id -> new Pose());
        float arc = HEAD_ARC;
        int seat = SbwVehicles.seatIndexOf(vehicle, soldier);
        boolean rifleFallback = seat >= 0 && !SbwVehicles.isDriver(vehicle, soldier)
            && seat == SbwVehicles.passengerWeaponStationControllerIndex(vehicle)
            && SbwVehicles.seatHasWeapon(vehicle, seat)
            && !SbwVehicles.seatHasLoadedWeapon(vehicle, seat)
            && SbwVehicles.seatAllowsRifleWhenWeaponEmpty(vehicle, seat);
        if (seat >= 0 && !rifleFallback) {
            if (!SbwVehicles.seatAllowsHeadRotation(vehicle, seat)) {
                arc = 0.0f;
            } else if (!SbwVehicles.seatAllowsHandheld(vehicle, seat)) {
                arc = ENCLOSED_HEAD_ARC;
            }
        }

        LivingEntity target = soldier.getTarget();
        float desiredYaw;
        float desiredPitch;
        if (arc > 0.0f && target != null && target.isAlive()
            && soldier.distanceToSqr(target) < 96.0 * 96.0) {
            Vec3 toTarget = target.getEyePosition().subtract(soldier.getEyePosition());
            double horizontal = Math.sqrt(toTarget.x * toTarget.x + toTarget.z * toTarget.z);
            float worldYaw = (float) Math.toDegrees(Math.atan2(-toTarget.x, toTarget.z));
            desiredYaw = Mth.wrapDegrees(worldYaw - hullYaw);
            desiredPitch = (float) -Math.toDegrees(Math.atan2(toTarget.y, Math.max(0.01, horizontal)));

            // Keep a target that crosses behind the soldier on the same side of the
            // seat arc; this avoids selecting the opposite 180-degree wrapped angle.
            if (Math.abs(desiredYaw) <= arc) {
                pose.side = 0;
            } else {
                int targetSide = desiredYaw >= 0.0f ? 1 : -1;
                if (pose.side == 0 || (Math.abs(desiredYaw) < 150.0f && targetSide != pose.side)) {
                    pose.side = targetSide;
                }
                desiredYaw = pose.side * arc;
            }
        } else {
            pose.side = 0;
            if (soldier.level().getGameTime() >= pose.nextGlanceAt) {
                var random = soldier.getRandom();
                float spread = arc * (random.nextFloat() < 0.25f ? 0.9f : 0.4f);
                pose.glanceYaw = Mth.clamp((float) (random.nextGaussian() * spread), -arc, arc);
                pose.glancePitch = (float) (random.nextGaussian() * 4.0);
                pose.nextGlanceAt = soldier.level().getGameTime()
                    + IDLE_GLANCE_MIN + random.nextInt(IDLE_GLANCE_MAX - IDLE_GLANCE_MIN + 1);
            }
            desiredYaw = pose.glanceYaw;
            desiredPitch = pose.glancePitch;
        }

        desiredYaw = Mth.clamp(desiredYaw, -arc, arc);
        desiredPitch = arc <= 0.0f ? 0.0f : Mth.clamp(desiredPitch, -PITCH_ARC, PITCH_ARC);
        writePose(soldier, vehicle, Mth.wrapDegrees(hullYaw + desiredYaw), desiredPitch);
    }

    /** No easing or previous-frame interpolation: the assigned head angles are authoritative. */
    private static void writePose(SoldierEntity soldier, Entity vehicle, float headYaw, float pitch) {
        float hullYaw = vehicle.getYRot();
        soldier.setYRot(hullYaw);
        soldier.yRotO = vehicle.yRotO;
        soldier.setYBodyRot(hullYaw);
        soldier.yBodyRotO = vehicle.yRotO;
        soldier.setYHeadRot(headYaw);
        soldier.yHeadRotO = headYaw;
        soldier.setXRot(pitch);
        soldier.xRotO = pitch;
    }

    public static void forget(SoldierEntity soldier) {
        if (soldier == null) return;
        POSES.remove(soldier.getUUID());
        MOUNTED.remove(soldier.getUUID());
    }
}
