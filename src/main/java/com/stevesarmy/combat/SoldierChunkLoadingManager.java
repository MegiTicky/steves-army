package com.stevesarmy.combat;

import com.stevesarmy.StevesArmyConfig;
import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.entity.SoldierEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Short-lived tickets: never persisted, and independent of recall's Forge tickets. */
@Mod.EventBusSubscriber(modid = StevesArmyMod.MODID)
public final class SoldierChunkLoadingManager {
    private static final TicketType<ChunkPos> TICKET = TicketType.create(
        "stevesarmy_soldier_activity", Comparator.comparingLong(ChunkPos::toLong), 60);
    // Radius 3 gives ticket level 30 at the center and entity-ticking level 31
    // in its eight neighbours. Minecraft also loads supporting border chunks.
    private static final int TICKET_RADIUS = 3;
    private static final Map<ServerLevel, Set<ChunkPos>> ACTIVE = new HashMap<>();

    private SoldierChunkLoadingManager() { }

    @SubscribeEvent
    public static void tick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (!StevesArmyConfig.SOLDIER_CHUNK_LOADING_ENABLED.get()) {
            releaseAll();
            return;
        }
        // Scan loaded entities, including those outside normal entity-ticking range.
        // The scan must not depend on a soldier ticking to renew its own ticket.
        if (event.getServer().getTickCount() % 20 != 0) return;
        double range = StevesArmyConfig.SOLDIER_CHUNK_LOADING_DISTANCE.get();
        double rangeSq = range * range;
        for (ServerLevel level : event.getServer().getAllLevels()) {
            Set<ChunkPos> desired = new HashSet<>();
            var players = level.players().stream().filter(player -> !player.isSpectator()).toList();
            if (!players.isEmpty()) {
                for (var entity : level.getAllEntities()) {
                    if (!(entity instanceof SoldierEntity soldier) || !soldier.isAlive()) continue;
                    boolean nearby = players.stream().anyMatch(player -> {
                        double dx = player.getX() - soldier.getX();
                        double dz = player.getZ() - soldier.getZ();
                        return dx * dx + dz * dz <= rangeSq;
                    });
                    if (nearby) desired.add(soldier.chunkPosition());
                }
            }
            Set<ChunkPos> previous = ACTIVE.getOrDefault(level, Set.of());
            // Add/refresh before removing old centers so moving squads keep continuity.
            for (ChunkPos pos : desired) {
                level.getChunkSource().addRegionTicket(TICKET, pos, TICKET_RADIUS, pos);
            }
            for (ChunkPos pos : previous) {
                if (!desired.contains(pos)) {
                    level.getChunkSource().removeRegionTicket(TICKET, pos, TICKET_RADIUS, pos);
                }
            }
            if (desired.isEmpty()) ACTIVE.remove(level);
            else ACTIVE.put(level, desired);
        }
    }

    @SubscribeEvent
    public static void stopping(ServerStoppingEvent event) {
        releaseAll();
    }

    private static void releaseAll() {
        ACTIVE.forEach((level, chunks) -> chunks.forEach(pos ->
            level.getChunkSource().removeRegionTicket(TICKET, pos, TICKET_RADIUS, pos)));
        ACTIVE.clear();
    }
}
