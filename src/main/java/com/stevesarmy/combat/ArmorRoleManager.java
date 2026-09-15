package com.stevesarmy.combat;

import com.stevesarmy.StevesArmyConfig;
import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.entity.SoldierRole;
import com.stevesarmy.squad.SquadData;
import com.stevesarmy.squad.SquadManager;
import net.minecraft.server.level.ServerLevel;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Per-squad anti-armor capability assessment for the vehicle doctrine.
 *
 * Hunter designation is ROLE-based: only {@code SoldierRole.ANTI_TANK}
 * soldiers are candidates, and only while an AT gun (a gun ID matching a
 * configured pattern, like {@code tacz:rpg7} matching "rpg") is somewhere in
 * their inventory — an AT soldier without a launcher acts as a rifleman, and
 * a rifleman who picks up a rocket launcher does not become a hunter. Hunters
 * also raise the launcher for a heavy-fire suppression ping, shelling the
 * cover blocks around the pinged position. The config override can force
 * either branch for testing.
 */
public final class ArmorRoleManager {
    private static final long REFRESH_INTERVAL_TICKS = 40;

    private record SquadArmorState(Set<UUID> hunterIds, long refreshedGameTime) {}
    private static final SquadArmorState EMPTY = new SquadArmorState(Set.of(), Long.MIN_VALUE);

    private static final Map<UUID, SquadArmorState> cacheBySquad = new HashMap<>();

    private ArmorRoleManager() {}

    /** True when this soldier's current gun is the squad's anti-armor weapon. */
    public static boolean isArmorHunter(SoldierEntity soldier) {
        if (StevesArmyConfig.getArmorDoctrineOverride() == StevesArmyConfig.ArmorDoctrineOverride.NO_AT) {
            return false;
        }
        SquadArmorState state = getState(soldier);
        return state != EMPTY && state.hunterIds().contains(soldier.getUUID());
    }

    /**
     * True when the squad counts as having anti-armor support. Riflemen only
     * suppress a vehicle (keeping its crew buttoned) in this state; otherwise
     * the whole squad uses the avoid-and-displace doctrine.
     */
    public static boolean squadHasAntiArmor(SoldierEntity soldier) {
        switch (StevesArmyConfig.getArmorDoctrineOverride()) {
            case NO_AT:
                return false;
            case FORCE_AT:
                return true;
            default:
                SquadArmorState state = getState(soldier);
                return state != EMPTY && !state.hunterIds().isEmpty();
        }
    }

    private static SquadArmorState getState(SoldierEntity soldier) {
        if (!(soldier.level() instanceof ServerLevel serverLevel)) {
            return EMPTY;
        }
        UUID squadId = soldier.getSquadId();
        if (squadId == null) {
            return EMPTY;
        }
        long gameTime = serverLevel.getGameTime();
        SquadArmorState cached = cacheBySquad.get(squadId);
        if (cached != null && gameTime - cached.refreshedGameTime() < REFRESH_INTERVAL_TICKS) {
            return cached;
        }

        Set<UUID> hunters = new HashSet<>();
        if (!StevesArmyConfig.getArmorAtGunPatterns().isEmpty()) {
            for (UUID memberId : getSquadMemberIds(serverLevel, squadId)) {
                if (!(serverLevel.getEntity(memberId) instanceof SoldierEntity member) || !member.isAlive()) {
                    continue;
                }
                if (member.getRole() == SoldierRole.ANTI_TANK && carriesAtGun(member)) {
                    hunters.add(memberId);
                }
            }
        }
        SquadArmorState state = new SquadArmorState(hunters, gameTime);
        cacheBySquad.put(squadId, state);
        return state;
    }

    private static Set<UUID> getSquadMemberIds(ServerLevel level, UUID squadId) {
        return new HashSet<>(SquadManager.get(level).getSquadById(squadId)
            .map(SquadData::getMemberIds)
            .orElse(List.of()));
    }

    /**
     * True when any persistent slot (sidearm, main hand, general) carries an
     * anti-armor gun. Role decides who hunts; this decides whether the hunter
     * can actually fight vehicles right now (launcher stashed = rifleman duty).
     */
    private static boolean carriesAtGun(SoldierEntity soldier) {
        com.stevesarmy.inventory.SoldierInventory inv = soldier.getSoldierInventory();
        if (inv == null) {
            return isAtGunStack(soldier.getMainHandItem());
        }
        for (int slot = com.stevesarmy.inventory.SoldierInventory.SLOT_SIDEARM;
             slot < com.stevesarmy.inventory.SoldierInventory.INVENTORY_SIZE; slot++) {
            if (isAtGunStack(inv.getItem(slot))) {
                return true;
            }
        }
        return false;
    }

    private static boolean gunMatchesAtPattern(SoldierEntity soldier) {
        return isAtGunStack(soldier.getMainHandItem());
    }

    /** True when this stack's gun ID matches the configured anti-armor patterns. */
    public static boolean isAtGunStack(net.minecraft.world.item.ItemStack stack) {
        if (!GunIntegration.isGun(stack)) {
            return false;
        }
        String gunId = GunIntegration.getGunId(stack);
        if (gunId == null || gunId.isEmpty()) {
            return false;
        }
        String lower = gunId.toLowerCase(Locale.ROOT);
        for (String pattern : StevesArmyConfig.getArmorAtGunPatterns()) {
            if (!pattern.isEmpty() && lower.contains(pattern.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}
