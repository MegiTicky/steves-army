package com.stevesarmy.combat.cover;

import com.stevesarmy.GameClock;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class CoverReservationManager {

    private static final Map<BlockPos, Set<UUID>> coverReservations = new ConcurrentHashMap<>();
    private static final int MAX_RESERVATIONS_PER_COVER = 1;
    private static final long RESERVATION_TIMEOUT_MS = 30000;
    private static final Map<UUID, Map<BlockPos, Long>> reservationTimestamps = new ConcurrentHashMap<>();
    /** Holder references so the tick sweep can keep physically-occupied spots reserved. */
    private static final Map<UUID, java.lang.ref.WeakReference<LivingEntity>> reservationHolders = new ConcurrentHashMap<>();
    private static final double PRONE_RESERVATION_SEPARATION_SQ = 9.0;
    private static final Map<UUID, ProneReservation> proneReservations = new ConcurrentHashMap<>();
    private static final Map<UUID, java.lang.ref.WeakReference<LivingEntity>> proneHolders = new ConcurrentHashMap<>();
    /** A reservation whose holder stands this close to the spot does not age out. */
    private static final double RESERVATION_OCCUPANCY_RADIUS_SQ = 6.25;

    private record ProneReservation(BlockPos position, long timestamp) {}

    public static boolean reserve(BlockPos coverPos, LivingEntity soldier) {
        if (coverPos == null || soldier == null) {
            return false;
        }

        UUID soldierUUID = soldier.getUUID();
        BlockPos key = coverPos.immutable();

        synchronized (coverReservations) {
            Set<UUID> reservations = coverReservations.computeIfAbsent(key, k -> ConcurrentHashMap.newKeySet());

            if (reservations.contains(soldierUUID)) {
                setTimestamp(soldierUUID, key, GameClock.nowMs(), soldier);
                return true;
            }

            if (reservations.size() >= MAX_RESERVATIONS_PER_COVER) {
                cleanupExpiredReservations(key, reservations);
                if (reservations.size() >= MAX_RESERVATIONS_PER_COVER) {
                    trace(soldier, "rejected: occupied", key);
                    return false;
                }
            }

            // Standing cover and prone lanes live in separate maps; without a
            // cross-check a rifleman's cover and an MG's prone lane could
            // claim the same spot cross-role.
            if (blockedByProneLaneNear(key, soldierUUID)) {
                trace(soldier, "rejected: prone lane separation", key);
                return false;
            }

            boolean added = reservations.add(soldierUUID);
            if (added) {
                setTimestamp(soldierUUID, key, GameClock.nowMs(), soldier);
                trace(soldier, "reserved", key);
            }
            return added;
        }
    }
    
    public static void release(BlockPos coverPos, LivingEntity soldier) {
        if (coverPos == null) {
            return;
        }
        
        BlockPos key = coverPos.immutable();

        if (soldier == null) {
            synchronized (coverReservations) {
                Set<UUID> reservations = coverReservations.remove(key);
                if (reservations != null) {
                    for (UUID uuid : reservations) {
                        removeTimestamp(uuid, key);
                    }
                }
            }
            return;
        }

        UUID soldierUUID = soldier.getUUID();
        synchronized (coverReservations) {
            Set<UUID> reservations = coverReservations.get(key);
            if (reservations != null) {
                if (reservations.remove(soldierUUID)) trace(soldier, "released", key);
                removeTimestamp(soldierUUID, key);
                
                if (reservations.isEmpty()) {
                    coverReservations.remove(key);
                }
            }
        }
    }
    
    public static void releaseAll(LivingEntity soldier) {
        if (soldier == null) {
            return;
        }

        UUID soldierUUID = soldier.getUUID();
        trace(soldier, "releaseAll", null);
        proneReservations.remove(soldierUUID);
        proneHolders.remove(soldierUUID);
        
        synchronized (coverReservations) {
            Iterator<Map.Entry<BlockPos, Set<UUID>>> iterator = coverReservations.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<BlockPos, Set<UUID>> entry = iterator.next();
                Set<UUID> reservations = entry.getValue();
                
                if (reservations.remove(soldierUUID)) {
                    removeTimestamp(soldierUUID, entry.getKey());
                    
                    if (reservations.isEmpty()) {
                        iterator.remove();
                    }
                }
            }
        }
    }
    
    public static boolean isAvailable(BlockPos coverPos) {
        return isAvailableFor(coverPos, null);
    }

    private static void trace(LivingEntity entity, String reason, BlockPos position) {
        if (com.stevesarmy.debug.trace.SoldierFlightRecorder.isRecording()
            && entity instanceof com.stevesarmy.entity.SoldierEntity soldier)
            com.stevesarmy.debug.trace.SoldierFlightRecorder.event(soldier, "cover-reservation", reason, position);
    }

    public static boolean isAvailableFor(BlockPos coverPos, LivingEntity soldier) {
        if (coverPos == null) {
            return false;
        }

        BlockPos key = coverPos.immutable();
        UUID exempt = soldier != null ? soldier.getUUID() : null;
        Set<UUID> reservations = coverReservations.get(key);

        if (reservations == null || reservations.isEmpty()) {
            return !blockedByProneLaneNear(key, exempt);
        }

        cleanupExpiredReservations(key, reservations);

        if (reservations.isEmpty()) {
            return !blockedByProneLaneNear(key, exempt);
        }

        // Own claim never blocks re-validation (and a nearby own prone lane is
        // exempted in the cross-check below) — only other soldiers' claims do.
        if (soldier != null && reservations.contains(soldier.getUUID())) {
            return true;
        }

        if (reservations.size() < MAX_RESERVATIONS_PER_COVER) {
            return !blockedByProneLaneNear(key, exempt);
        }

        return false;
    }
    
    public static int getReservationCount(BlockPos coverPos) {
        if (coverPos == null) {
            return 0;
        }
        
        BlockPos key = coverPos.immutable();
        Set<UUID> reservations = coverReservations.get(key);
        
        if (reservations == null) {
            return 0;
        }
        
        cleanupExpiredReservations(key, reservations);
        return reservations.size();
    }
    
    public static boolean isReservedBy(BlockPos coverPos, LivingEntity soldier) {
        if (coverPos == null || soldier == null) {
            return false;
        }
        
        BlockPos key = coverPos.immutable();
        Set<UUID> reservations = coverReservations.get(key);
        
        if (reservations == null) {
            return false;
        }
        
        UUID soldierUUID = soldier.getUUID();
        if (!reservations.contains(soldierUUID)) {
            return false;
        }
        if (isReservationExpired(soldierUUID, key, GameClock.nowMs())) {
            release(key, soldier);
            return false;
        }
        return true;
    }
    
    public static Set<BlockPos> getReservedPositions() {
        synchronized (coverReservations) {
            return new HashSet<>(coverReservations.keySet());
        }
    }

    /** Reserves an open-prone firing lane while keeping nearby soldiers apart. */
    public static boolean reserveProne(BlockPos position, LivingEntity soldier) {
        if (position == null || soldier == null) return false;
        UUID soldierUUID = soldier.getUUID();
        BlockPos key = position.immutable();
        synchronized (coverReservations) {
            cleanupProneReservations(GameClock.nowMs());
            ProneReservation current = proneReservations.get(soldierUUID);
            if (current != null && current.position().equals(key)) {
                proneReservations.put(soldierUUID, new ProneReservation(key, GameClock.nowMs()));
                proneHolders.put(soldierUUID, new java.lang.ref.WeakReference<>(soldier));
                return true;
            }
            // Reverse cross-check: a prone lane cannot claim ground next to a
            // reserved standing-cover spot (see reserve()).
            if (blockedByCoverReservationNear(key, soldierUUID)) {
                return false;
            }
            for (Map.Entry<UUID, ProneReservation> entry : proneReservations.entrySet()) {
                if (!entry.getKey().equals(soldierUUID)
                    && entry.getValue().position().distSqr(key) <= PRONE_RESERVATION_SEPARATION_SQ) {
                    return false;
                }
            }
            proneReservations.put(soldierUUID, new ProneReservation(key, GameClock.nowMs()));
            proneHolders.put(soldierUUID, new java.lang.ref.WeakReference<>(soldier));
            return true;
        }
    }

    public static boolean isProneAvailableFor(BlockPos position, LivingEntity soldier) {
        if (position == null) return false;
        BlockPos key = position.immutable();
        UUID exempt = soldier != null ? soldier.getUUID() : null;
        synchronized (coverReservations) {
            cleanupProneReservations(GameClock.nowMs());
            if (blockedByCoverReservationNear(key, exempt)) {
                return false;
            }
            for (Map.Entry<UUID, ProneReservation> entry : proneReservations.entrySet()) {
                if (soldier != null && entry.getKey().equals(soldier.getUUID())) continue;
                if (entry.getValue().position().distSqr(position) <= PRONE_RESERVATION_SEPARATION_SQ) {
                    return false;
                }
            }
            return true;
        }
    }

    public static void releaseProne(LivingEntity soldier) {
        if (soldier != null) {
            proneReservations.remove(soldier.getUUID());
            proneHolders.remove(soldier.getUUID());
        }
    }

    /** Reservation owners for debug display: standing cover pos -> claiming soldier UUIDs. */
    public static Map<BlockPos, List<UUID>> getReservationOwners() {
        Map<BlockPos, List<UUID>> result = new HashMap<>();
        synchronized (coverReservations) {
            for (Map.Entry<BlockPos, Set<UUID>> entry : coverReservations.entrySet()) {
                cleanupExpiredReservations(entry.getKey(), entry.getValue());
                if (!entry.getValue().isEmpty()) {
                    result.put(entry.getKey(), new ArrayList<>(entry.getValue()));
                }
            }
        }
        return result;
    }

    /** Prone-lane owners for debug display: soldier UUID -> lane position. */
    public static Map<UUID, BlockPos> getProneLaneOwners() {
        Map<UUID, BlockPos> result = new HashMap<>();
        synchronized (coverReservations) {
            cleanupProneReservations(GameClock.nowMs());
            for (Map.Entry<UUID, ProneReservation> entry : proneReservations.entrySet()) {
                result.put(entry.getKey(), entry.getValue().position());
            }
        }
        return result;
    }
    
    public static Map<BlockPos, Integer> getAllReservationCounts() {
        Map<BlockPos, Integer> result = new HashMap<>();
        
        synchronized (coverReservations) {
            for (Map.Entry<BlockPos, Set<UUID>> entry : coverReservations.entrySet()) {
                cleanupExpiredReservations(entry.getKey(), entry.getValue());
                result.put(entry.getKey(), entry.getValue().size());
            }
        }
        
        return result;
    }
    
    private static void setTimestamp(UUID soldierUUID, BlockPos coverPos, long time, LivingEntity holder) {
        reservationTimestamps
            .computeIfAbsent(soldierUUID, k -> new ConcurrentHashMap<>())
            .put(coverPos, time);
        if (holder != null) {
            reservationHolders.put(soldierUUID, new java.lang.ref.WeakReference<>(holder));
        }
    }

    private static void removeTimestamp(UUID soldierUUID, BlockPos coverPos) {
        Map<BlockPos, Long> perSoldier = reservationTimestamps.get(soldierUUID);
        if (perSoldier != null) {
            perSoldier.remove(coverPos);
            if (perSoldier.isEmpty()) {
                reservationTimestamps.remove(soldierUUID);
                reservationHolders.remove(soldierUUID);
            }
        }
    }

    /** True when another soldier's prone lane sits within the cross separation of {@code key}. */
    private static boolean blockedByProneLaneNear(BlockPos key, UUID exempt) {
        for (Map.Entry<UUID, ProneReservation> entry : proneReservations.entrySet()) {
            if (exempt != null && entry.getKey().equals(exempt)) continue;
            if (entry.getValue().position().distSqr(key) <= PRONE_RESERVATION_SEPARATION_SQ) {
                return true;
            }
        }
        return false;
    }

    /** True when another soldier's standing-cover reservation sits within the cross separation of {@code key}. */
    private static boolean blockedByCoverReservationNear(BlockPos key, UUID exempt) {
        for (Map.Entry<BlockPos, Set<UUID>> entry : coverReservations.entrySet()) {
            if (entry.getKey().distSqr(key) > PRONE_RESERVATION_SEPARATION_SQ) continue;
            if (exempt != null && entry.getValue().contains(exempt)) continue;
            return true;
        }
        return false;
    }
    
    private static void cleanupExpiredReservations(BlockPos coverPos, Set<UUID> reservations) {
        long currentTime = GameClock.nowMs();
        reservations.removeIf(uuid -> {
            Map<BlockPos, Long> perSoldier = reservationTimestamps.get(uuid);
            if (perSoldier == null) return true;
            // Expiration belongs to this specific (soldier, cover) pair. Do not
            // use the soldier's oldest reservation to release a newer cover.
            if (!isReservationExpired(uuid, coverPos, currentTime)) {
                return false;
            }
            removeTimestamp(uuid, coverPos);
            return true;
        });
    }

    private static boolean isReservationExpired(UUID soldierUUID, BlockPos coverPos, long now) {
        Map<BlockPos, Long> perSoldier = reservationTimestamps.get(soldierUUID);
        Long timestamp = perSoldier != null ? perSoldier.get(coverPos) : null;
        return timestamp == null || now - timestamp > RESERVATION_TIMEOUT_MS;
    }
    
    public static void clear() {
        synchronized (coverReservations) {
            coverReservations.clear();
            reservationTimestamps.clear();
            reservationHolders.clear();
            proneReservations.clear();
            proneHolders.clear();
        }
    }
    
    public static void tick() {
        synchronized (coverReservations) {
            long currentTime = GameClock.nowMs();

            // Occupancy keep-alive: a reservation whose holder still physically
            // occupies the spot does not age out. Goal interruptions clear the
            // cover bookkeeping but leave the soldier standing there; without
            // this the next searcher stacks into an occupied spot. Once the
            // holder walks away the stamp stops refreshing and the reservation
            // expires normally.
            for (Map.Entry<BlockPos, Set<UUID>> entry : coverReservations.entrySet()) {
                for (UUID uuid : entry.getValue()) {
                    refreshIfPhysicallyOccupied(uuid, entry.getKey(), currentTime, reservationHolders,
                        (u, t) -> refreshTimestamp(u, entry.getKey(), t));
                }
            }
            for (Map.Entry<UUID, ProneReservation> entry : proneReservations.entrySet()) {
                refreshIfPhysicallyOccupied(entry.getKey(), entry.getValue().position(), currentTime, proneHolders,
                    (u, t) -> proneReservations.put(u, new ProneReservation(entry.getValue().position(), t)));
            }

            cleanupProneReservations(currentTime);

            Iterator<Map.Entry<BlockPos, Set<UUID>>> iterator = coverReservations.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<BlockPos, Set<UUID>> entry = iterator.next();
                Set<UUID> reservations = entry.getValue();

                reservations.removeIf(uuid -> {
                    Map<BlockPos, Long> perSoldier = reservationTimestamps.get(uuid);
                    if (perSoldier == null) return true;
                    Long timestamp = perSoldier.get(entry.getKey());
                    if (timestamp == null || (currentTime - timestamp) > RESERVATION_TIMEOUT_MS) {
                        removeTimestamp(uuid, entry.getKey());
                        return true;
                    }
                    return false;
                });

                if (reservations.isEmpty()) {
                    iterator.remove();
                }
            }
        }
    }

    /** Refreshes only the wall-clock stamp; holder registration stays in {@link #setTimestamp}. */
    private static void refreshTimestamp(UUID soldierUUID, BlockPos coverPos, long time) {
        Map<BlockPos, Long> perSoldier = reservationTimestamps.get(soldierUUID);
        if (perSoldier != null) {
            perSoldier.put(coverPos, time);
        }
    }

    private static void refreshIfPhysicallyOccupied(UUID holderUuid, BlockPos pos, long now,
                                                    Map<UUID, java.lang.ref.WeakReference<LivingEntity>> holders,
                                                    java.util.function.BiConsumer<UUID, Long> refresh) {
        java.lang.ref.WeakReference<LivingEntity> ref = holders.get(holderUuid);
        LivingEntity holder = ref != null ? ref.get() : null;
        if (holder != null && holder.isAlive()
            && holder.position().distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(pos))
                <= RESERVATION_OCCUPANCY_RADIUS_SQ) {
            refresh.accept(holderUuid, now);
        }
    }

    private static void cleanupProneReservations(long currentTime) {
        proneReservations.entrySet().removeIf(entry ->
            currentTime - entry.getValue().timestamp() > RESERVATION_TIMEOUT_MS);
    }
}
