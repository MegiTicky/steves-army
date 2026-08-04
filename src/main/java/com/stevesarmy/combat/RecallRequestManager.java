package com.stevesarmy.combat;

import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.network.NetworkHandler;
import com.stevesarmy.network.SquadStatusSyncPacket;
import com.stevesarmy.squad.OwnedSoldierRegistry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraftforge.common.world.ForgeChunkManager;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Coordinates delayed recall while loading an unloaded source chunk. */
@Mod.EventBusSubscriber(modid = StevesArmyMod.MODID)
public final class RecallRequestManager {
    private static final int RECALL_DELAY_TICKS = 80;
    private static final int SOURCE_RESOLUTION_TIMEOUT_TICKS = 100;
    private static final Map<UUID, Request> REQUESTS = new HashMap<>();

    private RecallRequestManager() { }

    public static void start(ServerPlayer player, UUID soldierId) {
        MinecraftServer server = player.getServer();
        if (server == null || REQUESTS.containsKey(soldierId)) return;

        OwnedSoldierRegistry registry = OwnedSoldierRegistry.get(server);
        OwnedSoldierRegistry.Entry entry = registry.get(soldierId);
        if (entry == null || !player.getUUID().equals(entry.ownerId())) return;

        SoldierEntity loaded = findLoaded(server, soldierId);
        if (loaded != null && !loaded.isAlive()) loaded = null;

        ResourceKey<Level> sourceDimension = resolveDimension(entry.dimension());
        ServerLevel sourceLevel = sourceDimension != null ? server.getLevel(sourceDimension) : null;
        boolean ticketed = false;
        if (loaded == null) {
            if (sourceLevel == null) {
                fail(player, "Could not find the soldier's source dimension");
                return;
            }
            ChunkPos chunk = new ChunkPos(entry.position());
            ticketed = ForgeChunkManager.forceChunk(sourceLevel, StevesArmyMod.MODID, soldierId,
                chunk.x, chunk.z, true, true);
            if (!ticketed) {
                fail(player, "Could not load the soldier's source chunk");
                return;
            }
        }

        Request request = new Request(player.getUUID(), soldierId, sourceDimension,
            sourceLevel != null ? new ChunkPos(entry.position()) : null, ticketed, server.getTickCount());
        REQUESTS.put(soldierId, request);
        if (loaded != null) loaded.setRecallTicks(RECALL_DELAY_TICKS);
        registry.setRecallTicks(soldierId, RECALL_DELAY_TICKS);
        refreshStatus(player);
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        MinecraftServer server = event.getServer();
        for (Request request : new ArrayList<>(REQUESTS.values())) {
            ServerPlayer player = server.getPlayerList().getPlayer(request.ownerId());
            if (player == null) {
                cancel(server, request, null);
                continue;
            }

            SoldierEntity soldier = findLoaded(server, request.soldierId());
            int elapsed = server.getTickCount() - request.startTick();
            if (soldier != null && soldier.isAlive()) {
                int remaining = Math.max(0, RECALL_DELAY_TICKS - elapsed);
                soldier.setRecallTicks(remaining);
                registry(server).setRecallTicks(request.soldierId(), remaining);
                if (elapsed >= RECALL_DELAY_TICKS) {
                    SoldierEntity recalled = RecallHelper.executeRecall(soldier, player);
                    if (recalled != null && recalled.level() instanceof ServerLevel level) {
                        registry(server).refresh(recalled, level);
                    } else if (soldier.isAlive() && soldier.level() instanceof ServerLevel level) {
                        registry(server).refresh(soldier, level);
                    }
                    release(server, request);
                    refreshStatus(player);
                }
            } else if (elapsed >= SOURCE_RESOLUTION_TIMEOUT_TICKS) {
                registry(server).remove(request.soldierId());
                release(server, request);
                fail(player, "The soldier could not be found in its source chunk");
                refreshStatus(player);
            } else {
                registry(server).setRecallTicks(request.soldierId(), Math.max(0, RECALL_DELAY_TICKS - elapsed));
            }
        }
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        MinecraftServer server = event.getServer();
        for (Request request : new ArrayList<>(REQUESTS.values())) cancel(server, request, null);
        REQUESTS.clear();
    }

    private static OwnedSoldierRegistry registry(MinecraftServer server) {
        return OwnedSoldierRegistry.get(server);
    }

    @Nullable
    private static SoldierEntity findLoaded(MinecraftServer server, UUID soldierId) {
        for (ServerLevel level : server.getAllLevels()) {
            Entity entity = level.getEntity(soldierId);
            if (entity instanceof SoldierEntity soldier) return soldier;
        }
        return null;
    }

    @Nullable
    private static ResourceKey<Level> resolveDimension(String id) {
        ResourceLocation location = ResourceLocation.tryParse(id);
        return location == null ? null : ResourceKey.create(Registries.DIMENSION, location);
    }

    private static void release(MinecraftServer server, Request request) {
        REQUESTS.remove(request.soldierId());
        if (request.ticketed() && request.sourceDimension() != null && request.sourceChunk() != null) {
            ServerLevel level = server.getLevel(request.sourceDimension());
            if (level != null) {
                ChunkPos chunk = request.sourceChunk();
                ForgeChunkManager.forceChunk(level, StevesArmyMod.MODID, request.soldierId(),
                    chunk.x, chunk.z, false, true);
            }
        }
    }

    private static void cancel(MinecraftServer server, Request request, @Nullable ServerPlayer player) {
        SoldierEntity soldier = findLoaded(server, request.soldierId());
        if (soldier != null) soldier.setRecallTicks(0);
        release(server, request);
        if (player != null) refreshStatus(player);
    }

    private static void refreshStatus(ServerPlayer player) {
        NetworkHandler.sendTo(player, SquadStatusSyncPacket.createForPlayer(player));
    }

    private static void fail(ServerPlayer player, String message) {
        player.sendSystemMessage(net.minecraft.network.chat.Component.literal(message));
    }

    private record Request(UUID ownerId, UUID soldierId, @Nullable ResourceKey<Level> sourceDimension,
                           @Nullable ChunkPos sourceChunk, boolean ticketed, int startTick) { }
}
