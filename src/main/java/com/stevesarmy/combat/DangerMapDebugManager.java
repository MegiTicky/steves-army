package com.stevesarmy.combat;

import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.network.DangerMapDebugPacket;
import com.stevesarmy.network.NetworkHandler;
import com.stevesarmy.squad.SquadDangerMap;
import com.stevesarmy.squad.SquadData;
import com.stevesarmy.squad.SquadManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side subscriptions and snapshots for the danger-map overlay
 * (/stevesarmy_debug dangermap): the baked cells and active threat views of
 * the subscriber's own squads (default) or every squad with soldiers nearby
 * ({@code dangermap all}), packed into one packet.
 */
@Mod.EventBusSubscriber(modid = StevesArmyMod.MODID)
public final class DangerMapDebugManager {
    private static final double SOLDIER_SCAN_RADIUS = 96.0;
    private static final int SEND_INTERVAL_TICKS = 10;
    private static final int MAX_CELLS_PER_PLAYER = 2048;

    /** playerId → showAllSquads. Default view: only squads owned by the viewing player. */
    private static final java.util.Map<UUID, Boolean> SUBSCRIBERS = new ConcurrentHashMap<>();
    private static final int LOG_INTERVAL_TICKS = 40;
    private static int sendCountdown = SEND_INTERVAL_TICKS;
    private static int logCountdown = 0;

    private DangerMapDebugManager() {}

    public static boolean isEnabled(UUID playerId) {
        return SUBSCRIBERS.containsKey(playerId);
    }

    public static boolean setEnabled(ServerPlayer player, boolean enabled) {
        return setEnabled(player, enabled, false);
    }

    public static boolean setEnabled(ServerPlayer player, boolean enabled, boolean showAllSquads) {
        if (enabled) {
            SUBSCRIBERS.put(player.getUUID(), showAllSquads);
        } else {
            SUBSCRIBERS.remove(player.getUUID());
        }
        sendSnapshot(player);
        return enabled;
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || SUBSCRIBERS.isEmpty()) {
            return;
        }
        if (++sendCountdown < SEND_INTERVAL_TICKS) {
            return;
        }
        sendCountdown = 0;
        MinecraftServer server = event.getServer();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (SUBSCRIBERS.containsKey(player.getUUID())) {
                sendSnapshot(player);
            }
        }
    }

    public static void sendSnapshot(ServerPlayer player) {
        Boolean showAllSquads = SUBSCRIBERS.get(player.getUUID());
        if (showAllSquads == null) {
            NetworkHandler.sendTo(player, new DangerMapDebugPacket(false, List.of(), List.of(),
                List.of(), new DangerMapDebugPacket.Stats(0, 0, 0, 0, 0, 0)));
            return;
        }
        ServerLevel level = player.serverLevel();
        long gameTime = level.getGameTime();
        Vec3 center = player.position();

        Set<UUID> squadIds = new HashSet<>();
        List<SoldierEntity> soldiersNear = new ArrayList<>();
        boolean mineOnly = !showAllSquads;
        for (SoldierEntity soldier : level.getEntitiesOfClass(SoldierEntity.class,
                player.getBoundingBox().inflate(SOLDIER_SCAN_RADIUS), SoldierEntity::isAlive)) {
            if (soldier.getSquadId() == null) continue;
            if (mineOnly && !soldier.getOwnerUUID().map(player.getUUID()::equals).orElse(false)) {
                continue;
            }
            squadIds.add(soldier.getSquadId());
            soldiersNear.add(soldier);
        }

        List<DangerMapDebugPacket.Cell> cells = new ArrayList<>();
        List<DangerMapDebugPacket.Threat> threats = new ArrayList<>();
        int bakedCells = 0;
        int surfaceMiss = 0;
        Vec3 firstAnchor = null;
        for (UUID squadId : squadIds) {
            SquadData squad = SquadManager.get(level).getSquadById(squadId).orElse(null);
            if (squad == null) continue;
            SquadDangerMap map = squad.getDangerMap();
            bakedCells += map.bakedCellCount();
            surfaceMiss += map.debugSurfaceMissCount();
            if (firstAnchor == null && map.debugAnchor() != null) {
                firstAnchor = map.debugAnchor();
            }
            for (SquadDangerMap.NearCell cell : map.snapshotCells(
                    center.x, center.z, SOLDIER_SCAN_RADIUS, gameTime,
                    MAX_CELLS_PER_PLAYER - cells.size())) {
                long packed = new BlockPos((int) cell.centerX() - 1, (int) cell.surfaceY(),
                    (int) cell.centerZ() - 1).asLong();
                cells.add(new DangerMapDebugPacket.Cell(packed,
                    Math.round(cell.score()), cell.eyeExposed(), cell.legExposed()));
                if (cells.size() >= MAX_CELLS_PER_PLAYER) break;
            }
            for (SquadDangerMap.NearThreat threat : map.snapshotThreats()) {
                threats.add(new DangerMapDebugPacket.Threat(
                    new Vec3(threat.x(), threat.y(), threat.z()), threat.weight(), threat.hardTarget()));
            }
        }

        // pathfinding corridors: the most recent capture per soldier, nearest
        // soldiers first, so the overlay shows the costs the CURRENT plan used
        soldiersNear.sort(java.util.Comparator.comparingDouble(s -> s.distanceToSqr(center)));
        List<DangerMapDebugPacket.Corridor> corridors = new ArrayList<>();
        for (SoldierEntity soldier : soldiersNear) {
            if (corridors.size() >= 4) break;
            com.stevesarmy.entity.ai.pathfinding.DangerCorridor.CorridorDebug debug =
                com.stevesarmy.entity.ai.pathfinding.DangerCorridor.debugFor(soldier.getUUID());
            if (debug == null || gameTime - debug.gameTime() > 60) continue;
            SquadDangerMap squadMap = SquadManager.get(level).getSquadById(soldier.getSquadId())
                .map(SquadData::getDangerMap).orElse(null);
            List<DangerMapDebugPacket.Cell> corridorCells = new ArrayList<>();
            for (java.util.Map.Entry<Long, Float> entry : debug.cellDanger().entrySet()) {
                if (corridorCells.size() >= 256) break;
                long key = entry.getKey();
                int cx = (int) (key >> 32);
                int cz = (int) (key << 32 >> 32);
                SquadDangerMap.Cell mapCell = squadMap != null
                    ? squadMap.cellAt(cx * 2.0 + 1.0, cz * 2.0 + 1.0, gameTime) : null;
                int surfaceY = mapCell != null ? mapCell.surfaceY : soldier.blockPosition().getY();
                long packed = new BlockPos(cx * 2, surfaceY, cz * 2).asLong();
                corridorCells.add(new DangerMapDebugPacket.Cell(packed,
                    Math.round(entry.getValue() * 100.0f), false, false));
            }
            if (corridorCells.isEmpty()) continue;
            corridors.add(new DangerMapDebugPacket.Corridor(debug.target(), debug.roleWeight(),
                corridorCells));
        }

        DangerMapDebugPacket.Stats stats = new DangerMapDebugPacket.Stats(
            cells.size(), bakedCells, threats.size(),
            SquadDangerMap.debugMaxRaysUsedPerTick(),
            com.stevesarmy.StevesArmyConfig.getDangerRayBudgetPerTick(),
            surfaceMiss);
        NetworkHandler.sendTo(player, new DangerMapDebugPacket(true, cells, threats, corridors, stats));

        if (++logCountdown >= LOG_INTERVAL_TICKS) {
            logCountdown = 0;
            Vec3 anchor = firstAnchor;
            StevesArmyMod.LOGGER.info(
                "[DangerMap][debug] squads={} bakedCells={} sentCells={} threats={} raysUsed={} surfaceMiss={} anchor={}",
                squadIds.size(), bakedCells, cells.size(), threats.size(),
                SquadDangerMap.debugMaxRaysUsedPerTick(), surfaceMiss,
                anchor == null ? "none" : String.format("(%.0f, %.0f) dist=%.0f", anchor.x, anchor.z,
                    anchor.distanceTo(center)));
        }
    }
}
