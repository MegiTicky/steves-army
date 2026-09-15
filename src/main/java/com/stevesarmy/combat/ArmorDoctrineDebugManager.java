package com.stevesarmy.combat;

import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.network.ArmorDoctrineDebugPacket;
import com.stevesarmy.network.NetworkHandler;
import com.stevesarmy.squad.SquadData;
import com.stevesarmy.squad.SquadManager;
import com.stevesarmy.squad.SquadThreatIntel;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side subscriptions and snapshot building for armor-doctrine debug
 * rendering (/stevesarmy_debug armor). Snapshots describe the hard targets
 * known to squads near the subscriber and the per-soldier doctrine state:
 * hunter designation, exposure, duck/displace gates, and suppression claims.
 */
@Mod.EventBusSubscriber(modid = StevesArmyMod.MODID)
public final class ArmorDoctrineDebugManager {
    public static final int OFF = 0;
    public static final int MINIMAL = 1;
    public static final int VERBOSE = 2;

    private static final double SOLDIER_SCAN_RADIUS = 128.0;
    private static final int SEND_INTERVAL_TICKS = 10;

    private static final ConcurrentHashMap<UUID, Integer> MODES = new ConcurrentHashMap<>();
    private static int sendCountdown = SEND_INTERVAL_TICKS;

    private ArmorDoctrineDebugManager() {}

    public static int getMode(UUID playerId) {
        return MODES.getOrDefault(playerId, OFF);
    }

    public static int cycle(ServerPlayer player) {
        return setMode(player, (getMode(player.getUUID()) + 1) % 3);
    }

    public static int setMode(ServerPlayer player, int requestedMode) {
        int mode = Math.max(OFF, Math.min(VERBOSE, requestedMode));
        if (mode == OFF) {
            MODES.remove(player.getUUID());
        } else {
            MODES.put(player.getUUID(), mode);
        }
        sendSnapshot(player, mode);
        return mode;
    }

    public static String modeName(int mode) {
        return switch (mode) {
            case MINIMAL -> "MINIMAL";
            case VERBOSE -> "VERBOSE";
            default -> "OFF";
        };
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || MODES.isEmpty()) {
            return;
        }
        if (++sendCountdown < SEND_INTERVAL_TICKS) {
            return;
        }
        sendCountdown = 0;
        MinecraftServer server = event.getServer();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            int mode = getMode(player.getUUID());
            if (mode != OFF) {
                sendSnapshot(player, mode);
            }
        }
    }

    public static void sendSnapshot(ServerPlayer player, int mode) {
        if (mode == OFF) {
            NetworkHandler.sendTo(player, new ArmorDoctrineDebugPacket(mode, List.of(), List.of()));
            return;
        }
        ServerLevel level = player.serverLevel();

        List<ArmorDoctrineDebugPacket.Contact> contacts = new ArrayList<>();
        List<ArmorDoctrineDebugPacket.Soldier> soldiers = new ArrayList<>();
        Set<UUID> visitedSquads = new HashSet<>();

        for (SoldierEntity soldier : level.getEntitiesOfClass(SoldierEntity.class,
                player.getBoundingBox().inflate(SOLDIER_SCAN_RADIUS), SoldierEntity::isAlive)) {
            SquadThreatIntel intel = intelFor(soldier, level);
            if (intel != null && soldier.getSquadId() != null
                && visitedSquads.add(soldier.getSquadId())) {
                collectContacts(intel, level.getGameTime(), contacts);
            }
            ArmorThreatScanner.ArmorContact armor = ArmorThreatScanner.getPrimaryArmorThreat(soldier);
            ItemStack held = soldier.getMainHandItem();
            boolean hunter = ArmorRoleManager.isArmorHunter(soldier);
            boolean launcherHeld = ArmorRoleManager.isAtGunStack(held);
            boolean launcherStored = SoldierWeaponSelector.hasLauncher(soldier);
            boolean visibleEntityTarget = soldier.getTarget() != null && soldier.getTarget().isAlive()
                && TargetAcquisition.hasLineOfSight(soldier, soldier.getTarget());
            boolean vehicleSelected = hunter && launcherHeld && armor != null && !visibleEntityTarget;
            double contactDistance = armor == null ? -1.0 : soldier.position().distanceTo(armor.aimPoint());
            String combatState = vehicleSelected ? "vehicle" : visibleEntityTarget ? "entity" : "idle";
            soldiers.add(new ArmorDoctrineDebugPacket.Soldier(
                soldier.getUUID(), soldier.getEyePosition(), hunter, launcherHeld, launcherStored,
                vehicleSelected, contactDistance, GunIntegration.getGunId(held),
                soldier.getPeekController().getState().name(), soldier.isLowCrouching(), soldier.isCrawlMoving(),
                GunIntegration.isReloading(soldier), GunIntegration.isBolting(soldier), GunIntegration.isDrawing(soldier),
                GunIntegration.getAimProgress(soldier), GunIntegration.getShootCoolDown(soldier),
                GunIntegration.getCurrentAmmo(soldier), currentCoverPos(soldier),
                ArmorThreatScanner.getLastFiringSolution(soldier), combatState));
        }

        NetworkHandler.sendTo(player, new ArmorDoctrineDebugPacket(mode, contacts, soldiers));
    }

    private static void collectContacts(SquadThreatIntel intel, long gameTime,
                                        List<ArmorDoctrineDebugPacket.Contact> out) {
        for (SquadThreatIntel.ThreatKnowledge knowledge : intel.getHardTargetThreats(gameTime)) {
            if (knowledge.lastKnownPosition == null) {
                continue;
            }
            Vec3 hull = Vec3.atCenterOf(knowledge.lastKnownPosition);
            Vec3 aim = knowledge.lastVisibleAimPoint != null ? knowledge.lastVisibleAimPoint : hull;
            out.add(new ArmorDoctrineDebugPacket.Contact(
                knowledge.threatEntityId, aim, hull, knowledge.lastKnownVelocity,
                knowledge.lastKnownHullCorners,
                knowledge.isSuppressed, gameTime - knowledge.lastSeenTime, knowledge.accuracy,
                knowledge.vehicleClass));
        }
    }

    @Nullable
    private static Vec3 currentCoverPos(SoldierEntity soldier) {
        com.stevesarmy.combat.cover.CoverPoint cover =
            soldier.getCoverBehaviorManager().getCurrentCover();
        return cover != null ? Vec3.atCenterOf(cover.getPosition()) : null;
    }

    @Nullable
    private static SquadThreatIntel intelFor(SoldierEntity soldier, ServerLevel level) {
        UUID squadId = soldier.getSquadId();
        if (squadId == null) {
            return null;
        }
        return SquadManager.get(level).getSquadById(squadId)
            .map(SquadData::getThreatIntel)
            .orElse(null);
    }
}
