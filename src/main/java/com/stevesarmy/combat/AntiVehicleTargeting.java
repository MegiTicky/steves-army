package com.stevesarmy.combat;

import com.stevesarmy.compat.sbw.SbwCompat;
import com.stevesarmy.compat.sbw.SbwVehicles;
import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.inventory.SoldierInventory;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

import javax.annotation.Nullable;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Selects occupied hostile SBW vehicles and decides whether the soldier should
 * use a launcher against the hull or shoot an exposed crew member.
 */
public final class AntiVehicleTargeting {

    private static final double SCAN_RADIUS = 72.0D;
    private static final double MIN_ROCKET_RANGE = 8.0D;

    public enum Plan {
        ROCKET_AT_HULL,
        SHOOT_THE_CREW,
        NONE
    }

    public record Decision(Plan plan, @Nullable CombatTarget target) {
        public static final Decision NONE = new Decision(Plan.NONE, null);
    }

    private AntiVehicleTargeting() {
    }

    public static boolean isLauncher(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        if (SbwCompat.isLoaded() && SbwCompat.isGun(stack)) {
            try {
                com.atsuishio.superbwarfare.data.gun.GunData data = com.atsuishio.superbwarfare.data.gun.GunData.from(stack);
                com.atsuishio.superbwarfare.data.gun.GunType type = data.get(com.atsuishio.superbwarfare.data.gun.GunProp.GUN_TYPE);
                if (type == com.atsuishio.superbwarfare.data.gun.GunType.DIRECT_LAUNCHER || type == com.atsuishio.superbwarfare.data.gun.GunType.CURVED_LAUNCHER) {
                    return true;
                }
            } catch (Throwable ignored) {}
        }
        ResourceLocation reg = ForgeRegistries.ITEMS.getKey(stack.getItem());
        if (reg != null) {
            String path = reg.getPath().toLowerCase(Locale.ROOT);
            if (path.contains("rpg") || path.contains("launcher") || path.contains("bazooka")
                || path.contains("panzer") || path.contains("smaw") || path.contains("at4")
                || path.contains("missile") || path.contains("stinger") || path.contains("javelin")
                || path.contains("carl_gustaf")) {
                return true;
            }
        }
        return false;
    }

    public static int findLauncherSlot(SoldierEntity soldier) {
        if (soldier == null) return -1;
        ItemStack main = soldier.getMainHandItem();
        if (isLauncher(main) && (GunIntegration.getCurrentAmmo(main) > 0 || GunIntegration.canReload(soldier))) {
            return SoldierInventory.SLOT_MAIN_HAND;
        }
        SoldierInventory inv = soldier.getSoldierInventory();
        if (inv != null) {
            for (int i = SoldierInventory.SLOT_GENERAL_START; i < SoldierInventory.INVENTORY_SIZE; i++) {
                ItemStack stack = inv.getItem(i);
                if (isLauncher(stack)) {
                    int ammo = GunIntegration.getCurrentAmmo(stack);
                    if (ammo > 0 || soldier.hasInfiniteReserveAmmo() || GunIntegration.getAmmoCountForGun(stack, stack) > 0) {
                        return i;
                    }
                    for (int j = SoldierInventory.SLOT_GENERAL_START; j < SoldierInventory.INVENTORY_SIZE; j++) {
                        ItemStack possibleAmmo = inv.getItem(j);
                        if (!possibleAmmo.isEmpty() && GunIntegration.getAmmoCountForGun(stack, possibleAmmo) > 0) {
                            return i;
                        }
                    }
                }
            }
        }
        return -1;
    }

    public static boolean hasExplosiveLauncher(LivingEntity entity) {
        if (entity == null) return false;

        ItemStack held = entity.getMainHandItem();
        if (isLauncher(held)) return true;

        if (!SbwCompat.isGun(held)) return false;

        String type = GunIntegration.getGunTabType(entity);
        if (type.contains("launcher")) return true;

        return GunIntegration.getEffectiveRange(entity) > 0
            && type.contains("special")
            && GunIntegration.getMagazineSize(entity) <= 4;
    }

    public static Decision decide(SoldierEntity soldier,
                                  List<LivingEntity> knownEnemies) {
        if (!SbwCompat.hasVehicles()) return Decision.NONE;

        List<Entity> vehicles = soldier.level().getEntities(
            soldier,
            soldier.getBoundingBox().inflate(SCAN_RADIUS),
            e -> SbwCompat.isVehicle(e)
                && !SbwVehicles.isWrecked(e)
                && hasHostileCrew(soldier, e, knownEnemies));

        Entity vehicle = vehicles.stream()
            .min(Comparator.comparingDouble(soldier::distanceToSqr))
            .orElse(null);

        if (vehicle == null) return Decision.NONE;

        double distance = Math.sqrt(soldier.distanceToSqr(vehicle));
        boolean hasLauncher = findLauncherSlot(soldier) != -1;
        if (hasLauncher && distance >= MIN_ROCKET_RANGE) {
            return new Decision(
                Plan.ROCKET_AT_HULL, CombatTarget.ofVehicle(vehicle));
        }

        LivingEntity crew = firstHostileCrew(soldier, vehicle, knownEnemies);
        return crew == null
            ? Decision.NONE
            : new Decision(Plan.SHOOT_THE_CREW, CombatTarget.of(crew));
    }

    private static boolean hasHostileCrew(SoldierEntity soldier, Entity vehicle,
                                          List<LivingEntity> knownEnemies) {
        return firstHostileCrew(soldier, vehicle, knownEnemies) != null;
    }

    @Nullable
    private static LivingEntity firstHostileCrew(SoldierEntity soldier,
                                                 Entity vehicle,
                                                 List<LivingEntity> knownEnemies) {
        for (Entity passenger : vehicle.getPassengers()) {
            if (!(passenger instanceof LivingEntity crew)) continue;
            if (crew == soldier) continue;
            if (soldier.isFriendlyTo(crew)) continue;
            if (knownEnemies.contains(crew)
                || TargetAcquisition.isValidTarget(soldier, crew)) {
                return crew;
            }
        }
        return null;
    }
}