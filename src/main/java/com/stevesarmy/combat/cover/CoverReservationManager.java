package com.stevesarmy.combat.cover;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class CoverReservationManager {
    
    private static final Map<BlockPos, Set<UUID>> coverReservations = new ConcurrentHashMap<>();
    private static final int MAX_RESERVATIONS_PER_COVER = 2;
    private static final long RESERVATION_TIMEOUT_MS = 30000;
    private static final Map<UUID, Long> reservationTimestamps = new ConcurrentHashMap<>();
    
    public static boolean reserve(BlockPos coverPos, LivingEntity soldier) {
        if (coverPos == null || soldier == null) {
            return false;
        }
        
        UUID soldierUUID = soldier.getUUID();
        BlockPos key = coverPos.immutable();
        
        synchronized (coverReservations) {
            Set<UUID> reservations = coverReservations.computeIfAbsent(key, k -> ConcurrentHashMap.newKeySet());
            
            if (reservations.contains(soldierUUID)) {
                reservationTimestamps.put(soldierUUID, System.currentTimeMillis());
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
                reservationTimestamps.put(soldierUUID, System.currentTimeMillis());
            }
            return added;
        }
    }
    
    public static void release(BlockPos coverPos, LivingEntity soldier) {
        if (coverPos == null || soldier == null) {
            return;
        }
        
        UUID soldierUUID = soldier.getUUID();
        BlockPos key = coverPos.immutable();
        
        synchronized (coverReservations) {
            Set<UUID> reservations = coverReservations.get(key);
            if (reservations != null) {
                reservations.remove(soldierUUID);
                reservationTimestamps.remove(soldierUUID);
                
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
                    reservationTimestamps.remove(soldierUUID);
                    
                    if (reservations.isEmpty()) {
                        iterator.remove();
                    }
                }
            }
        }
    }
    
    public static boolean isAvailable(BlockPos coverPos) {
        if (coverPos == null) {
            return false;
        }
        
        BlockPos key = coverPos.immutable();
        Set<UUID> reservations = coverReservations.get(key);
        
        if (reservations == null || reservations.isEmpty()) {
            return true;
        }
        
        cleanupExpiredReservations(reservations);
        
        return reservations.size() < MAX_RESERVATIONS_PER_COVER;
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
    
    private static void cleanupExpiredReservations(Set<UUID> reservations) {
        long currentTime = System.currentTimeMillis();
        reservations.removeIf(uuid -> {
            Long timestamp = reservationTimestamps.get(uuid);
            if (timestamp == null) {
                return true;
            }
            return (currentTime - timestamp) > RESERVATION_TIMEOUT_MS;
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
                    Long timestamp = reservationTimestamps.get(uuid);
                    if (timestamp == null || (currentTime - timestamp) > RESERVATION_TIMEOUT_MS) {
                        reservationTimestamps.remove(uuid);
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
