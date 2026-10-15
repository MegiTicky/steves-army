package com.stevesarmy.combat;

import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.entity.ai.CoverPositionController;
import com.stevesarmy.entity.ai.SoldierSpeedController;
import com.stevesarmy.network.MovementSpeedDebugPacket;
import com.stevesarmy.network.NetworkHandler;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Mod.EventBusSubscriber(modid = StevesArmyMod.MODID)
public final class MovementSpeedDebugManager {
    private static final double RANGE = 64.0;
    private static final double RANGE_SQUARED = RANGE * RANGE;
    private static final int MAX_PER_VIEWER = 32;
    private static final int SEND_INTERVAL_TICKS = 5;
    private static final Set<UUID> SUBSCRIBERS = ConcurrentHashMap.newKeySet();
    private static final Map<HistoryKey, History> HISTORIES = new HashMap<>();
    private static int sendCountdown;

    private MovementSpeedDebugManager() {}

    public static boolean isEnabled(UUID playerId) {
        return SUBSCRIBERS.contains(playerId);
    }

    public static boolean setEnabled(ServerPlayer player, boolean enabled) {
        if (enabled) SUBSCRIBERS.add(player.getUUID());
        else SUBSCRIBERS.remove(player.getUUID());
        sendSnapshot(player);
        return enabled;
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (SUBSCRIBERS.isEmpty()) {
            HISTORIES.clear();
            return;
        }

        MinecraftServer server = event.getServer();
        List<ServerPlayer> viewers = server.getPlayerList().getPlayers().stream()
            .filter(player -> SUBSCRIBERS.contains(player.getUUID())).toList();
        Set<HistoryKey> sampled = new HashSet<>();
        Map<ServerPlayer, List<SoldierEntity>> nearbyByViewer = new HashMap<>();
        for (ServerPlayer viewer : viewers) {
            List<SoldierEntity> nearby = viewer.serverLevel().getEntitiesOfClass(SoldierEntity.class,
                viewer.getBoundingBox().inflate(RANGE), soldier -> soldier.isAlive()
                    && soldier.distanceToSqr(viewer) <= RANGE_SQUARED).stream()
                .sorted(Comparator.comparingDouble(viewer::distanceToSqr))
                .limit(MAX_PER_VIEWER).toList();
            nearbyByViewer.put(viewer, nearby);
            for (SoldierEntity soldier : nearby) {
                HistoryKey key = key(soldier);
                if (sampled.add(key)) {
                    HISTORIES.computeIfAbsent(key, ignored -> new History()).sample(soldier.position());
                }
            }
        }
        HISTORIES.keySet().retainAll(sampled);

        if (++sendCountdown < SEND_INTERVAL_TICKS) return;
        sendCountdown = 0;
        for (ServerPlayer viewer : viewers) {
            sendSnapshot(viewer, nearbyByViewer.getOrDefault(viewer, List.of()));
        }
    }

    public static void sendSnapshot(ServerPlayer player) {
        if (!SUBSCRIBERS.contains(player.getUUID())) {
            NetworkHandler.sendTo(player, new MovementSpeedDebugPacket(false, List.of()));
            return;
        }
        List<SoldierEntity> nearby = player.serverLevel().getEntitiesOfClass(SoldierEntity.class,
            player.getBoundingBox().inflate(RANGE), soldier -> soldier.isAlive()
                && soldier.distanceToSqr(player) <= RANGE_SQUARED).stream()
            .sorted(Comparator.comparingDouble(player::distanceToSqr))
            .limit(MAX_PER_VIEWER).toList();
        sendSnapshot(player, nearby);
    }

    private static void sendSnapshot(ServerPlayer player, List<SoldierEntity> nearby) {
        List<MovementSpeedDebugPacket.Entry> entries = nearby.stream()
            .sorted(Comparator.comparingDouble(player::distanceToSqr))
            .limit(MAX_PER_VIEWER)
            .map(MovementSpeedDebugManager::snapshot)
            .toList();
        NetworkHandler.sendTo(player, new MovementSpeedDebugPacket(true, entries));
    }

    private static MovementSpeedDebugPacket.Entry snapshot(SoldierEntity soldier) {
        History history = HISTORIES.computeIfAbsent(key(soldier), ignored -> new History());
        CoverPositionController moveControl = soldier.getMoveControl() instanceof CoverPositionController controller
            ? controller : null;
        SoldierSpeedController speed = moveControl == null ? null : moveControl.getSpeedController();
        String owner = moveControl == null ? "vanilla" : moveControl.debugMoveSource();
        if (moveControl != null && !moveControl.debugMoveReason().isBlank()) {
            owner += ": " + moveControl.debugMoveReason();
        }
        var policy = soldier.getMovementController().snapshot();
        owner += " | " + policy.mode() + "/" + policy.fireMode()
            + " " + policy.phaseTicksRemaining() + "t " + policy.transitionReason();
        String posture = soldier.isFiringProne() ? "FIRING_PRONE"
            : soldier.isLowCrouching() ? "LOW_CROUCH" : soldier.getPose().name();
        boolean collision = soldier.horizontalCollision || soldier.verticalCollision;
        return new MovementSpeedDebugPacket.Entry(soldier.getId(), soldier.getUUID(),
            soldier.getName().getString(), soldier.position(), history.displacement,
            history.measuredSpeed, history.averageSpeed(), history.peakSpeed(),
            speed == null ? 0 : speed.requestedModifier(), speed == null ? 0 : speed.targetSpeed(),
            speed == null ? 0 : speed.appliedSpeed(), soldier.getAttributeValue(Attributes.MOVEMENT_SPEED),
            speed == null ? 0 : speed.incomingSpeed(), speed == null ? 0 : speed.spentBudget(),
            speed != null && speed.impulseBypass(), speed == null ? "vanilla" : speed.limitingReason(), owner,
            soldier.getCoverBehaviorManager().getState().name(), posture,
            soldier.onGround(), collision);
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        UUID id = event.getEntity().getUUID();
        SUBSCRIBERS.remove(id);
    }

    @SubscribeEvent
    public static void onDimensionChange(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (SUBSCRIBERS.contains(player.getUUID())) {
            NetworkHandler.sendTo(player, new MovementSpeedDebugPacket(true, List.of()));
        }
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        SUBSCRIBERS.clear();
        HISTORIES.clear();
        sendCountdown = 0;
    }

    private static final class History {
        private Vec3 lastPosition;
        private Vec3 displacement = Vec3.ZERO;
        private double measuredSpeed;
        private final Deque<Double> recent = new ArrayDeque<>(100);

        private void sample(Vec3 position) {
            displacement = lastPosition == null ? Vec3.ZERO : position.subtract(lastPosition);
            measuredSpeed = displacement.horizontalDistance();
            lastPosition = position;
            recent.addLast(measuredSpeed);
            while (recent.size() > 100) recent.removeFirst();
        }

        private double averageSpeed() {
            if (recent.isEmpty()) return 0;
            return recent.stream().skip(Math.max(0, recent.size() - 10)).mapToDouble(Double::doubleValue)
                .average().orElse(0);
        }

        private double peakSpeed() {
            return recent.stream().mapToDouble(Double::doubleValue).max().orElse(0);
        }
    }

    private static HistoryKey key(SoldierEntity soldier) {
        return new HistoryKey(soldier.level().dimension(), soldier.getUUID());
    }

    private record HistoryKey(ResourceKey<Level> dimension, UUID entityId) {}
}
