package com.stevesarmy.squad;

import com.stevesarmy.combat.VisibilityRay;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Short-lived runtime cache for geometry shared by soldiers in one squad.
 * It deliberately stores no entity references and is not persisted with squad data.
 */
public final class SquadCoverPeekabilityCache {
    private static final long ENTRY_TTL_TICKS = 10L;
    private static final int MAX_ENTRIES = 2_048;

    private final LinkedHashMap<CacheKey, CachedValue> entries = new LinkedHashMap<>(256, 0.75f, true);
    private long lastPruneTick = Long.MIN_VALUE;

    public VisibilityRay.Result getContactVisibility(Level level, Vec3 origin, UUID threatId,
                                                      Vec3 exposedPoint, long currentTick) {
        ContactKey key = new ContactKey(origin, threatId, exposedPoint);
        CachedValue cached = get(key, currentTick);
        if (cached != null) {
            return (VisibilityRay.Result) cached.value();
        }

        VisibilityRay.Result result = VisibilityRay.trace(level, origin, exposedPoint, null);
        put(key, result, currentTick);
        return result;
    }

    public Float getConeCoverage(Vec3 origin, Direction threatDirection, long currentTick) {
        CachedValue cached = get(new ConeKey(origin, threatDirection), currentTick);
        return cached != null ? (Float) cached.value() : null;
    }

    public void putConeCoverage(Vec3 origin, Direction threatDirection, float coverage, long currentTick) {
        put(new ConeKey(origin, threatDirection), coverage, currentTick);
    }

    private CachedValue get(CacheKey key, long currentTick) {
        pruneExpired(currentTick);
        CachedValue cached = entries.get(key);
        if (cached == null || cached.expiresAtTick() < currentTick) {
            if (cached != null) {
                entries.remove(key);
            }
            return null;
        }
        return cached;
    }

    private void put(CacheKey key, Object value, long currentTick) {
        pruneExpired(currentTick);
        while (entries.size() >= MAX_ENTRIES) {
            Iterator<Map.Entry<CacheKey, CachedValue>> iterator = entries.entrySet().iterator();
            if (!iterator.hasNext()) {
                break;
            }
            iterator.next();
            iterator.remove();
        }
        entries.put(key, new CachedValue(value, currentTick + ENTRY_TTL_TICKS));
    }

    private void pruneExpired(long currentTick) {
        if (lastPruneTick == currentTick) {
            return;
        }
        lastPruneTick = currentTick;
        Iterator<Map.Entry<CacheKey, CachedValue>> iterator = entries.entrySet().iterator();
        while (iterator.hasNext()) {
            if (iterator.next().getValue().expiresAtTick() < currentTick) {
                iterator.remove();
            }
        }
    }

    private interface CacheKey {}

    private record ContactKey(Vec3 origin, UUID threatId, Vec3 exposedPoint) implements CacheKey {}

    private record ConeKey(Vec3 origin, Direction threatDirection) implements CacheKey {}

    private record CachedValue(Object value, long expiresAtTick) {}
}
