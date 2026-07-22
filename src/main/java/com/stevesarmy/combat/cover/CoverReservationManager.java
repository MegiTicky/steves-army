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
                cleanupExpiredReservations(reservations);
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
        
        cleanupExpiredReservations(reservations);
        
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
        
        cleanupExpiredReservations(reservations);
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
        
        return reservations.contains(soldier.getUUID());
    }
    
    public static Set<BlockPos> getReservedPositions() {
        synchronized (coverReservations) {
            return new HashSet<>(coverReservations.keySet());
        }
    }
    
    public static Map<BlockPos, Integer> getAllReservationCounts() {
        Map<BlockPos, Integer> result = new HashMap<>();
        
        synchronized (coverReservations) {
            for (Map.Entry<BlockPos, Set<UUID>> entry : coverReservations.entrySet()) {
                cleanupExpiredReservations(entry.getValue());
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
    
    private static void cleanupExpiredReservations(Set<UUID> reservations) {
        long currentTime = System.currentTimeMillis();
        reservations.removeIf(uuid -> {
            Map<BlockPos, Long> perSoldier = reservationTimestamps.get(uuid);
            if (perSoldier == null) return true;
            // If any of this soldier's reservations are expired, remove them all
            // (simplified: check the oldest timestamp)
            long oldest = perSoldier.values().stream().mapToLong(Long::longValue).min().orElse(0);
            boolean expired = (currentTime - oldest) > RESERVATION_TIMEOUT_MS;
            if (expired) {
                // Actually remove the specific cover timestamps that are expired
                perSoldier.entrySet().removeIf(e -> (currentTime - e.getValue()) > RESERVATION_TIMEOUT_MS);
                if (perSoldier.isEmpty()) {
                    reservationTimestamps.remove(uuid);
                }
                // Check if this specific UUID is still in any reservation set
                return true;
            }
            return false;
        });
    }
    
    public static void clear() {
        synchronized (coverReservations) {
            coverReservations.clear();
            reservationTimestamps.clear();
        }
    }
    
    public static void tick() {
        synchronized (coverReservations) {
            long currentTime = System.currentTimeMillis();
            
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
}