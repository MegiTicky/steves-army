package com.stevesarmy.combat.cover;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class CoverReservationManager {
    
    private static final Map<BlockPos, Set<UUID>> coverReservations = new ConcurrentHashMap<>();
    private static final int MAX_RESERVATIONS_PER_COVER = 1;
    private static final long RESERVATION_TIMEOUT_MS = 30000;
    private static final Map<UUID, Map<BlockPos, Long>> reservationTimestamps = new ConcurrentHashMap<>();
    private static final double PRONE_RESERVATION_SEPARATION_SQ = 9.0;
    private static final Map<UUID, ProneReservation> proneReservations = new ConcurrentHashMap<>();

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
                setTimestamp(soldierUUID, key, System.currentTimeMillis());
                return true;
            }
            
            if (reservations.size() >= MAX_RESERVATIONS_PER_COVER) {
                cleanupExpiredReservations(key, reservations);
                if (reservations.size() >= MAX_RESERVATIONS_PER_COVER) {
                    return false;
                }
            }
            
            boolean added = reservations.add(soldierUUID);
            if (added) {
                setTimestamp(soldierUUID, key, System.currentTimeMillis());
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
                reservations.remove(soldierUUID);
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
        proneReservations.remove(soldierUUID);
        
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

    public static boolean isAvailableFor(BlockPos coverPos, LivingEntity soldier) {
        if (coverPos == null) {
            return false;
        }
        
        BlockPos key = coverPos.immutable();
        Set<UUID> reservations = coverReservations.get(key);
        
        if (reservations == null || reservations.isEmpty()) {
            return true;
        }
        
        cleanupExpiredReservations(key, reservations);
        
        if (reservations.size() < MAX_RESERVATIONS_PER_COVER) {
            return true;
        }
        
        if (soldier != null && reservations.contains(soldier.getUUID())) {
            return true;
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
        if (isReservationExpired(soldierUUID, key, System.currentTimeMillis())) {
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
            cleanupProneReservations(System.currentTimeMillis());
            ProneReservation current = proneReservations.get(soldierUUID);
            if (current != null && current.position().equals(key)) {
                proneReservations.put(soldierUUID, new ProneReservation(key, System.currentTimeMillis()));
                return true;
            }
            for (Map.Entry<UUID, ProneReservation> entry : proneReservations.entrySet()) {
                if (!entry.getKey().equals(soldierUUID)
                    && entry.getValue().position().distSqr(key) <= PRONE_RESERVATION_SEPARATION_SQ) {
                    return false;
                }
            }
            proneReservations.put(soldierUUID, new ProneReservation(key, System.currentTimeMillis()));
            return true;
        }
    }

    public static boolean isProneAvailableFor(BlockPos position, LivingEntity soldier) {
        if (position == null) return false;
        synchronized (coverReservations) {
            cleanupProneReservations(System.currentTimeMillis());
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
        if (soldier != null) proneReservations.remove(soldier.getUUID());
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
    
    private static void setTimestamp(UUID soldierUUID, BlockPos coverPos, long time) {
        reservationTimestamps
            .computeIfAbsent(soldierUUID, k -> new ConcurrentHashMap<>())
            .put(coverPos, time);
    }
    
    private static void removeTimestamp(UUID soldierUUID, BlockPos coverPos) {
        Map<BlockPos, Long> perSoldier = reservationTimestamps.get(soldierUUID);
        if (perSoldier != null) {
            perSoldier.remove(coverPos);
            if (perSoldier.isEmpty()) {
                reservationTimestamps.remove(soldierUUID);
            }
        }
    }
    
    private static void cleanupExpiredReservations(BlockPos coverPos, Set<UUID> reservations) {
        long currentTime = System.currentTimeMillis();
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
            proneReservations.clear();
        }
    }
    
    public static void tick() {
        synchronized (coverReservations) {
            long currentTime = System.currentTimeMillis();
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

    private static void cleanupProneReservations(long currentTime) {
        proneReservations.entrySet().removeIf(entry ->
            currentTime - entry.getValue().timestamp() > RESERVATION_TIMEOUT_MS);
    }
}
