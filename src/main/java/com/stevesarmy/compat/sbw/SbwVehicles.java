package com.stevesarmy.compat.sbw;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import com.stevesarmy.entity.SoldierEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Reflective facade over an SBW vehicle. All driving, seating, health and
 * weapon-station access for soldiers goes through here so no other class in
 * the mod references SBW types.
 */
public final class SbwVehicles {

    private SbwVehicles() {
    }

    // -- drone exclusion ------------------------------------------------------

    public static boolean isDrone(Entity entity) {
        if (entity == null) return false;
        String name = entity.getClass().getName();
        return name.contains("DroneEntity") || name.contains(".drone.") || name.endsWith(".DroneEntity");
    }

    /** A drone is hostile when its controller is hostile; an unlinked drone is treated as a threat. */
    public static boolean isHostileDrone(SoldierEntity soldier, Entity drone) {
        if (soldier == null || !isDrone(drone)) return false;
        Object controller = SbwReflection.call(drone, new String[]{"getController"});
        if (controller instanceof LivingEntity living && living.isAlive()) {
            return !soldier.isFriendlyTo(living) && soldier.isProactiveTarget(living);
        }
        return true;
    }

    // -- capacity and seating -------------------------------------------------

    public static int maxPassengers(Entity vehicle) {
        if (isDrone(vehicle)) return 0;
        return SbwReflection.asInt(
            SbwReflection.get(vehicle, "maxPassengers", "getMaxPassengers"), 0);
    }

    public static int freeSeats(Entity vehicle) {
        if (!SbwCompat.isVehicle(vehicle) || isDrone(vehicle)) return 0;
        return Math.max(0, maxPassengers(vehicle) - vehicle.getPassengers().size());
    }

    public static boolean hasFreeSeat(Entity vehicle) {
        return freeSeats(vehicle) > 0;
    }

    public static int seatIndexOf(Entity vehicle, Entity passenger) {
        if (isDrone(vehicle)) return -1;
        return SbwReflection.asInt(
            SbwReflection.call(vehicle, new String[]{"getSeatIndex"}, passenger), -1);
    }

    public static Entity passengerAt(Entity vehicle, int index) {
        if (isDrone(vehicle)) return null;
        Object o = SbwReflection.call(vehicle, new String[]{"getNthEntity"}, index);
        return o instanceof Entity e ? e : null;
    }

    /** Seat 0 is the driver seat in every native SBW vehicle. */
    public static Entity driver(Entity vehicle) {
        return passengerAt(vehicle, 0);
    }

    public static boolean isDriver(Entity vehicle, Entity passenger) {
        return seatIndexOf(vehicle, passenger) == 0;
    }

    public static int turretControllerIndex(Entity vehicle) {
        if (isDrone(vehicle)) return -1;
        return SbwReflection.asInt(
            SbwReflection.get(vehicle, "turretControllerIndex", "getTurretControllerIndex"), 0);
    }

    public static int passengerWeaponStationControllerIndex(Entity vehicle) {
        if (isDrone(vehicle)) return -1;
        return SbwReflection.asInt(SbwReflection.get(vehicle,
            "passengerWeaponStationControllerIndex", "getPassengerWeaponStationControllerIndex"), -1);
    }

    private static Object seatInfo(Entity vehicle, int seatIndex) {
        if (isDrone(vehicle)) return null;
        return SbwReflection.call(vehicle, new String[]{"getSeat"}, seatIndex);
    }

    /**
     * Open seats permit handheld weapons. SBW models this as SeatInfo.banHand,
     * so a soldier may shoot its own gun only when the seat does not ban hands.
     */
    public static boolean seatAllowsHandheld(Entity vehicle, int seatIndex) {
        Object seat = seatInfo(vehicle, seatIndex);
        if (seat == null) return false;
        Object ban = SbwReflection.get(seat, "banHand", "getBanHand", "isBanHand");
        // SBW also bans handheld input automatically when the seat has a
        // mounted weapon, even when the JSON BanHand flag is false.
        return !SbwReflection.asBool(ban, false) && !seatHasWeapon(vehicle, seatIndex);
    }

    /** Whether an unarmed passenger seat is exposed and can be used by a rifleman. */
    public static boolean seatHidesPassenger(Entity vehicle, int seatIndex) {
        Object seat = seatInfo(vehicle, seatIndex);
        return seat != null && SbwReflection.asBool(
            SbwReflection.get(seat, "hidePassenger", "getHidePassenger", "isHidePassenger"), false);
    }

    /** First free visible passenger seat where an ordinary rider may use a handheld weapon. */
    public static int firstFreeVisibleHandheldPassengerSeat(Entity vehicle) {
        for (int seat = 1; seat < maxPassengers(vehicle); seat++) {
            if (passengerAt(vehicle, seat) == null && seatAllowsHandheld(vehicle, seat)
                && !seatHidesPassenger(vehicle, seat)) {
                return seat;
            }
        }
        return -1;
    }

    /**
     * Whether a seated soldier may use its own rifle after that seat's mounted
     * weapon runs out. SBW's normal hand-use check also rejects every seat with
     * a mounted weapon, even when it is empty; for fallback fire only BanHand
     * should continue to block the rifle.
     */
    public static boolean seatAllowsRifleWhenWeaponEmpty(Entity vehicle, int seatIndex) {
        Object seat = seatInfo(vehicle, seatIndex);
        if (seat == null) return false;
        Object ban = SbwReflection.get(seat, "banHand", "getBanHand", "isBanHand");
        return !SbwReflection.asBool(ban, false);
    }

    /** Weapon station names available to the given seat, possibly empty. */
    @SuppressWarnings("unchecked")
    public static List<String> seatWeapons(Entity vehicle, int seatIndex) {
        Object seat = seatInfo(vehicle, seatIndex);
        if (seat == null) return List.of();
        Object weapons = SbwReflection.call(seat, new String[]{"weapons", "getWeapons"});
        if (weapons instanceof List<?> list) {
            List<String> out = new ArrayList<>(list.size());
            for (Object o : list) {
                if (o instanceof String s) out.add(s);
            }
            return out;
        }
        return List.of();
    }

    public static boolean seatHasWeapon(Entity vehicle, int seatIndex) {
        return !seatWeapons(vehicle, seatIndex).isEmpty();
    }

    /** Hide flag used by SBW's RenderPlayerEvent.Pre handler for this rider. */
    public static boolean hidesPassenger(Entity vehicle, Entity passenger) {
        if (!SbwCompat.isVehicle(vehicle) || passenger == null || isDrone(vehicle)) return false;
        return SbwReflection.asBool(
            SbwReflection.call(vehicle, new String[]{"hidePassenger"}, passenger), false);
    }

    /** An exposed rider can be engaged at its head, unlike a hidden enclosed crewman. */
    public static boolean isExposedRider(LivingEntity target) {
        Entity vehicle = target == null ? null : target.getVehicle();
        return SbwCompat.isVehicle(vehicle) && !isDrone(vehicle) && !hidesPassenger(vehicle, target);
    }

    /**
     * A buttoned-up tank with hidden crew in seats 0/1 and no exposed gunner
     * in seat 2 is a cover threat for infantry. They cannot hit the riders with
     * rifles, so their response should be cover or anti-armor weapons.
     */
    public static boolean hasButtonedUpCrewWithoutVisibleGunner(SoldierEntity observer, Entity vehicle) {
        if (!SbwCompat.isVehicle(vehicle) || isDrone(vehicle)) return false;

        Entity exposedGunner = passengerAt(vehicle, 2);
        if (exposedGunner != null && exposedGunner.isAlive()
            && !hidesPassenger(vehicle, exposedGunner)) return false;

        for (int seat = 0; seat < 2; seat++) {
            Entity passenger = passengerAt(vehicle, seat);
            if (passenger instanceof LivingEntity living && living.isAlive()
                && !observer.isFriendlyTo(living) && hidesPassenger(vehicle, passenger)) {
                return true;
            }
        }
        return false;
    }

    public static boolean seatAllowsHeadRotation(Entity vehicle, int seatIndex) {
        Object seat = seatInfo(vehicle, seatIndex);
        return seat != null && SbwReflection.asBool(
            SbwReflection.get(seat, "canRotateHead", "getCanRotateHead", "isCanRotateHead"), true);
    }

    public static String getGunName(Entity vehicle, int seatIndex) {
        if (isDrone(vehicle)) return null;
        Object res = SbwReflection.call(vehicle, new String[]{"getGunName"}, seatIndex);
        return res instanceof String s ? s : null;
    }

    public static int weaponIndex(Entity vehicle, int seatIndex) {
        if (isDrone(vehicle)) return -1;
        return SbwReflection.asInt(
            SbwReflection.call(vehicle, new String[]{"getWeaponIndex"}, seatIndex), -1);
    }

    /** Select a weapon from this seat's own weapon list (never another seat's gun). */
    public static boolean setWeaponIndex(Entity vehicle, int seatIndex, int weaponIndex) {
        if (!SbwCompat.isVehicle(vehicle) || isDrone(vehicle) || weaponIndex < 0) return false;
        SbwReflection.invoke(vehicle, new String[]{"setWeaponIndex"}, seatIndex, weaponIndex);
        return weaponIndex(vehicle, seatIndex) == weaponIndex;
    }

    public static boolean weaponReloading(Entity vehicle, int seatIndex, int weaponIndex) {
        Object gun = SbwReflection.call(vehicle, new String[]{"getGunData"}, seatIndex, weaponIndex);
        return SbwReflection.asBool(SbwReflection.call(gun, new String[]{"reloading", "isReloading"}), false);
    }

    public static boolean weaponReady(Entity vehicle, int seatIndex, int weaponIndex) {
        List<String> weapons = seatWeapons(vehicle, seatIndex);
        if (weaponIndex < 0 || weaponIndex >= weapons.size()
            || weaponReloading(vehicle, seatIndex, weaponIndex)) return false;

        Object gun = SbwReflection.call(vehicle, new String[]{"getGunData"}, seatIndex, weaponIndex);
        if (gun != null) {
            // SBW's canShoot accounts for energy weapons, cooldown and ammo cost.
            Object canShoot = SbwReflection.call(gun, new String[]{"canShoot"}, vehicle);
            if (canShoot instanceof Boolean ready) return ready;
        }
        Object ammo = SbwReflection.call(vehicle, new String[]{"getAmmoCount"}, weapons.get(weaponIndex));
        return SbwReflection.asInt(ammo, weaponIndex == weaponIndex(vehicle, seatIndex)
            ? ammoCount(vehicle, seatIndex) : 0) > 0;
    }

    /** Ammo test that does not treat a short firing cooldown as an empty weapon. */
    public static boolean weaponHasAmmo(Entity vehicle, int seatIndex, int weaponIndex) {
        List<String> weapons = seatWeapons(vehicle, seatIndex);
        if (weaponIndex < 0 || weaponIndex >= weapons.size()) return false;
        Object ammo = SbwReflection.call(vehicle, new String[]{"getAmmoCount"}, weapons.get(weaponIndex));
        int fallback = weaponIndex == weaponIndex(vehicle, seatIndex) ? ammoCount(vehicle, seatIndex) : 0;
        return SbwReflection.asInt(ammo, fallback) > 0;
    }

    /** Usable without considering a short firing cooldown. Includes energy/infinite ammo. */
    public static boolean weaponAvailable(Entity vehicle, int seatIndex, int weaponIndex) {
        List<String> weapons = seatWeapons(vehicle, seatIndex);
        if (weaponIndex < 0 || weaponIndex >= weapons.size()
            || weaponReloading(vehicle, seatIndex, weaponIndex)) return false;
        Object gun = SbwReflection.call(vehicle, new String[]{"getGunData"}, seatIndex, weaponIndex);
        if (gun != null) {
            Object supplier = SbwReflection.get(vehicle, "ammoSupplier", "getAmmoSupplier");
            if (supplier == null) supplier = vehicle;
            Object enough = SbwReflection.call(gun, new String[]{"hasEnoughAmmoToShoot"}, supplier);
            if (enough instanceof Boolean value) return value;
            Object ready = SbwReflection.call(gun, new String[]{"canShoot"}, supplier);
            if (Boolean.TRUE.equals(ready)) return true;
        }
        return weaponHasAmmo(vehicle, seatIndex, weaponIndex);
    }

    /** Read a processed gun property, including the currently selected ammo override. */
    private static Object gunProperty(Object gun, String property) {
        Class<?> props = SbwReflection.cls("com.atsuishio.superbwarfare.data.gun.GunProp");
        java.lang.reflect.Field field = SbwReflection.field(props, property);
        if (gun == null || field == null) return null;
        try {
            return SbwReflection.call(gun, new String[]{"get"}, field.get(null));
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
    }

    private static boolean enabledSbwExplosionSetting(String name) {
        Class<?> config = SbwReflection.cls("com.atsuishio.superbwarfare.config.server.ExplosionConfig");
        java.lang.reflect.Field field = SbwReflection.field(config, name);
        if (field == null) return false;
        try {
            return Boolean.TRUE.equals(SbwReflection.call(field.get(null), new String[]{"get"}));
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return false;
        }
    }

    /** Fail closed: only SBW's actual AP cannon shell, with block penetration enabled. */
    public static boolean weaponCanPenetrateCover(Entity vehicle, int seat, int weapon) {
        Object gun = SbwReflection.call(vehicle, new String[]{"getGunData"}, seat, weapon);
        if (!"AP".equals(gunProperty(gun, "SHELL_TYPE"))
            || !Boolean.TRUE.equals(gunProperty(gun, "EXPLOSION_DESTROY"))
            || !enabledSbwExplosionSetting("EXPLOSION_DESTROY")
            || !enabledSbwExplosionSetting("EXTRA_EXPLOSION_EFFECT")) return false;
        Object projectile = gunProperty(gun, "PROJECTILE");
        Object id = SbwReflection.get(projectile, "getId", "getItemId", "itemId");
        return "superbwarfare:cannon_shell".equals(String.valueOf(id));
    }

    /** Fire through the same weapon-specific path used by SBW; caller owns rate/aim checks. */
    public static boolean shootAtPosition(Entity vehicle, LivingEntity gunner, int seat, Vec3 pos) {
        String name = getGunName(vehicle, seat);
        if (name == null || !Boolean.TRUE.equals(
            SbwReflection.call(vehicle, new String[]{"canShoot"}, gunner))) return false;
        return SbwReflection.invoke(vehicle, new String[]{"vehicleShoot"}, gunner, name, null, pos);
    }

    /** Whether the currently selected weapon in a seat has ammunition available. */
    public static boolean seatHasLoadedWeapon(Entity vehicle, int seatIndex) {
        int selected = weaponIndex(vehicle, seatIndex);
        if (selected < 0) selected = 0;
        return weaponHasAmmo(vehicle, seatIndex, selected)
            || weaponReady(vehicle, seatIndex, selected);
    }

    // -- health & fuel --------------------------------------------------------

    public static float health(Entity vehicle) {
        return SbwReflection.asFloat(SbwReflection.get(vehicle, "health", "getHealth"), 0f);
    }

    public static float maxHealth(Entity vehicle) {
        return SbwReflection.asFloat(SbwReflection.get(vehicle, "maxHealth", "getMaxHealth"), 0f);
    }

    /** A vehicle is wrecked once health is non-positive or it left the world. */
    public static boolean isWrecked(Entity vehicle) {
        if (vehicle == null || vehicle.isRemoved()) return true;
        if (!SbwCompat.isVehicle(vehicle)) return false;
        return health(vehicle) <= 0f;
    }

    /** Ensure the vehicle has sufficient energy to drive and operate systems. */
    public static void ensureFuel(Entity vehicle) {
        if (!SbwCompat.isVehicle(vehicle) || isDrone(vehicle)) return;
        Object energyObj = SbwReflection.get(vehicle, "energy", "getEnergy");
        int energy = SbwReflection.asInt(energyObj, 0);
        Object maxEnergyObj = SbwReflection.get(vehicle, "maxEnergy", "getMaxEnergy");
        int maxEnergy = SbwReflection.asInt(maxEnergyObj, 100000);
        if (energy < 2000 && maxEnergy > 0) {
            SbwReflection.set(vehicle, Math.max(maxEnergy, 50000), "energy");
        }
    }

    public static float power(Entity vehicle) {
        return SbwReflection.asFloat(SbwReflection.get(vehicle, "power", "getPower"), 0.0f);
    }

    public static void setPower(Entity vehicle, float p) {
        if (isDrone(vehicle)) return;
        SbwReflection.set(vehicle, p, "power");
    }

    // -- driving inputs -------------------------------------------------------

    public static void input(Entity vehicle, String name, boolean down) {
        if (isDrone(vehicle)) return;
        SbwReflection.set(vehicle, down, name);
    }

    public static void forward(Entity vehicle, boolean down) {
        input(vehicle, "forwardInputDown", down);
    }

    public static void back(Entity vehicle, boolean down) {
        input(vehicle, "backInputDown", down);
    }

    public static void left(Entity vehicle, boolean down) {
        input(vehicle, "leftInputDown", down);
    }

    public static void right(Entity vehicle, boolean down) {
        input(vehicle, "rightInputDown", down);
    }

    public static void up(Entity vehicle, boolean down) {
        input(vehicle, "upInputDown", down);
    }

    public static void down(Entity vehicle, boolean down) {
        input(vehicle, "downInputDown", down);
    }

    public static void fire(Entity vehicle, boolean down) {
        input(vehicle, "fireInputDown", down);
    }

    public static void sprint(Entity vehicle, boolean down) {
        input(vehicle, "sprintInputDown", down);
    }

    public static void updateInputs(Entity vehicle, boolean forward, boolean back, boolean left, boolean right, boolean sprint) {
        if (!SbwCompat.isVehicle(vehicle) || isDrone(vehicle)) return;
        short keys = 0;
        if (left) keys |= 0b00000001;
        if (right) keys |= 0b00000010;
        if (forward) keys |= 0b00000100;
        if (back) keys |= 0b00001000;
        if (sprint) keys |= 256;
        SbwReflection.call(vehicle, new String[]{"processInput"}, keys);

        forward(vehicle, forward);
        back(vehicle, back);
        left(vehicle, left);
        right(vehicle, right);
        sprint(vehicle, sprint);
    }

    public static void clearInputs(Entity vehicle) {
        if (isDrone(vehicle)) return;
        updateInputs(vehicle, false, false, false, false, false);
        up(vehicle, false);
        down(vehicle, false);
        fire(vehicle, false);
    }

    // -- weapon stations ------------------------------------------------------

    public static int ammoCount(Entity vehicle, int seatIndex) {
        if (isDrone(vehicle)) return 0;
        return SbwReflection.asInt(
            SbwReflection.call(vehicle, new String[]{"getAmmoCount"}, seatIndex), 0);
    }

    public static int weaponHeat(Entity vehicle, int seatIndex) {
        if (isDrone(vehicle)) return 0;
        return SbwReflection.asInt(
            SbwReflection.call(vehicle, new String[]{"getWeaponHeat"}, seatIndex), 0);
    }

    public static Vec3 shootPos(Entity vehicle, int seatIndex) {
        if (isDrone(vehicle)) return vehicle.position();
        Object res = SbwReflection.call(vehicle, new String[]{"getShootPos"}, seatIndex, 1.0f);
        return res instanceof Vec3 v ? v : vehicle.position();
    }

    public static float projectileVelocity(Entity vehicle, int seatIndex) {
        if (isDrone(vehicle)) return 30.0f;
        return SbwReflection.asFloat(
            SbwReflection.call(vehicle, new String[]{"getProjectileVelocity"}, seatIndex), 30.0f);
    }

    public static float projectileGravity(Entity vehicle, int seatIndex) {
        if (isDrone(vehicle)) return 0.0f;
        return SbwReflection.asFloat(
            SbwReflection.call(vehicle, new String[]{"getProjectileGravity"}, seatIndex), 0.0f);
    }

    /** Preserve SBW's ballistic lead while the Steve's Army crew controller adds subtle aim error. */
    public static Vec3 firingSolution(Entity vehicle, int seatIndex, Vec3 targetPos, Vec3 targetVelocity) {
        Vec3 origin = shootPos(vehicle, seatIndex);
        if (targetPos == null) return Vec3.ZERO;
        Class<?> rangeTool = SbwReflection.cls("com.atsuishio.superbwarfare.tools.RangeTool");
        Object solution = SbwReflection.callStatic(rangeTool,
            new String[]{"calculateFiringSolution"}, origin, targetPos,
            targetVelocity == null ? Vec3.ZERO : targetVelocity,
            (double) projectileVelocity(vehicle, seatIndex),
            (double) projectileGravity(vehicle, seatIndex));
        return solution instanceof Vec3 vector && vector.lengthSqr() > 1.0e-6
            ? vector : targetPos.subtract(origin);
    }

    public static void aimWeapon(Entity vehicle, int seatIndex, Vec3 aimVec) {
        if (!SbwCompat.isVehicle(vehicle) || isDrone(vehicle) || aimVec == null) return;
        int turretIdx = turretControllerIndex(vehicle);
        int weaponStationIdx = passengerWeaponStationControllerIndex(vehicle);

        if (seatIndex == turretIdx) {
            if (SbwReflection.invoke(vehicle, new String[]{"turretAutoAimFromVector"}, aimVec)) return;
        } else if (seatIndex == weaponStationIdx) {
            if (SbwReflection.invoke(vehicle, new String[]{"passengerWeaponAutoAimFormVector"}, aimVec)) return;
        } else {
            return;
        }

        Class<?> utilsClass = SbwReflection.cls("com.atsuishio.superbwarfare.entity.vehicle.utils.VehicleWeaponUtils");
        if (utilsClass != null) {
            SbwReflection.callStatic(utilsClass,
                new String[]{seatIndex == turretIdx ? "turretAutoAimFromVector" : "passengerWeaponAutoAimFormVector"},
                vehicle, aimVec);
        }
    }

    /** SBW normally mirrors Mob.setTarget through an event, but a target may predate boarding. */
    public static void syncAutoTarget(Entity vehicle, int seatIndex, LivingEntity target) {
        if (!SbwCompat.isVehicle(vehicle) || isDrone(vehicle)) return;
        String id = target == null ? "undefined" : target.getStringUUID();
        if (seatIndex == turretControllerIndex(vehicle)) {
            SbwReflection.set(vehicle, id, "aiTurretTargetUUID");
        } else if (seatIndex == passengerWeaponStationControllerIndex(vehicle)) {
            SbwReflection.set(vehicle, id, "aiPassengerWeaponTargetUUID");
        }
    }

    public static float turretMinPitch(Entity vehicle) {
        return SbwReflection.asFloat(SbwReflection.get(vehicle, "turretMinPitch", "getTurretMinPitch"), -10.0f);
    }

    public static float turretMaxPitch(Entity vehicle) {
        return SbwReflection.asFloat(SbwReflection.get(vehicle, "turretMaxPitch", "getTurretMaxPitch"), 20.0f);
    }

    public static boolean shoot(Entity vehicle, LivingEntity gunner, UUID targetId, Vec3 targetPos) {
        if (vehicle == null || isDrone(vehicle) || gunner == null || targetPos == null) return false;
        int seatIndex = seatIndexOf(vehicle, gunner);
        String weaponName = getGunName(vehicle, seatIndex);
        if (weaponName == null) return false;

        if (targetId != null && SbwReflection.invoke(vehicle,
            new String[]{"vehicleShoot"}, gunner, targetId, targetPos)) return true;
        if (SbwReflection.invoke(vehicle, new String[]{"vehicleShoot"}, gunner, weaponName, targetPos)) return true;
        return SbwReflection.invoke(vehicle, new String[]{"vehicleShoot"}, gunner, targetPos);
    }

    public static void destroy(Entity vehicle) {
        SbwReflection.call(vehicle, new String[]{"destroy"});
    }

    // -- classification, for movement tuning ----------------------------------

    public enum Kind { GROUND, TRACKED, WHEELCHAIR, BOAT, HELICOPTER, AIRCRAFT, AIRSHIP, STATIC, UNKNOWN }

    /** SBW's data is authoritative: many unrelated vehicles share one generic Java class. */
    public static String engineType(Entity vehicle) {
        Object data = SbwReflection.call(vehicle, new String[]{"computed"});
        Object engine = SbwReflection.get(data, "getEngineType", "engineType");
        return engine instanceof Enum<?> e ? e.name() : null;
    }

    public static Kind kind(Entity vehicle) {
        if (!SbwCompat.isVehicle(vehicle) || isDrone(vehicle)) return Kind.UNKNOWN;
        String engine = engineType(vehicle);
        if (engine != null) {
            switch (engine) {
                case "TRACK": return Kind.TRACKED;
                case "WHEEL": return Kind.GROUND;
                case "WHEELCHAIR": return Kind.WHEELCHAIR;
                case "SHIP": return Kind.BOAT;
                case "HELICOPTER": return Kind.HELICOPTER;
                case "AIRCRAFT": case "TOM6": return Kind.AIRCRAFT;
                case "AIRSHIP": return Kind.AIRSHIP;
                case "FIXED": case "EMPTY": return Kind.STATIC;
                default: break;
            }
        }
        String type = com.stevesarmy.compat.SbwCompat.getVehicleTypeName(vehicle);
        if (type != null) {
            switch (type) {
                case "AIRPLANE": return Kind.AIRCRAFT;
                case "HELICOPTER": return Kind.HELICOPTER;
                case "AIRSHIP": return Kind.AIRSHIP;
                case "BOAT": return Kind.BOAT;
                case "ARTILLERY": case "DEFENSE": return Kind.STATIC;
                default: break;
            }
        }
        // Compatibility fallback for older SBW builds without computed engine data.
        String n = vehicle.getClass().getName().toLowerCase(java.util.Locale.ROOT);
        if (n.contains("wheelchair") || n.contains("wheel_chair")) return Kind.WHEELCHAIR;
        if (n.contains("artillery") || n.contains("mortar") || n.contains("tower")
            || n.contains("annihilator") || n.contains("laser")) return Kind.STATIC;
        if (n.contains("boat") || n.contains("ship")) return Kind.BOAT;
        if (n.contains("heli") || n.contains("ah6") || n.contains("mi28")) return Kind.HELICOPTER;
        if (n.contains("a10") || n.contains("ju87") || n.contains("j16")
            || n.contains("ac130") || n.contains("plane")) return Kind.AIRCRAFT;
        if (n.contains("kirov") || n.contains("ghast")) return Kind.AIRSHIP;
        if (n.contains("tank") || n.contains("m1a2") || n.contains("t90")
            || n.contains("ztz") || n.contains("bmp") || n.contains("bradley") || n.contains("yx100")
            || n.contains("prism")) return Kind.TRACKED;
        return Kind.GROUND;
    }

    public static boolean isAirborne(Entity vehicle) {
        Kind k = kind(vehicle);
        return k == Kind.HELICOPTER || k == Kind.AIRCRAFT || k == Kind.AIRSHIP;
    }

    public static boolean canDrive(Entity vehicle) {
        if (isDrone(vehicle)) return false;
        Kind k = kind(vehicle);
        return k != Kind.STATIC && k != Kind.UNKNOWN;
    }
}
