package com.stevesarmy.squad;

import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.entity.TargetEntity;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.scores.Team;

import javax.annotation.Nullable;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Per-owner friend/foe overrides. The built-in rules in
 * {@link SoldierEntity#isFriendlyTo} stay the default layer; entries here take
 * precedence and are resolved as player &gt; team &gt; category. Keyed by the
 * squad owner, applied by all of that owner's soldiers.
 */
public class FofSettings extends SavedData {
    private static final String DATA_NAME = "steves_army_fof";
    private final Map<UUID, OwnerFof> owners = new HashMap<>();

    public static FofSettings get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
            FofSettings::load, FofSettings::new, DATA_NAME);
    }

    public OwnerFof owner(UUID ownerId) {
        return owners.computeIfAbsent(ownerId, ignored -> new OwnerFof());
    }

    @Nullable
    public OwnerFof peek(UUID ownerId) {
        return owners.get(ownerId);
    }

    public boolean clearOwner(UUID ownerId) {
        if (owners.remove(ownerId) != null) {
            setDirty();
            return true;
        }
        return false;
    }

    /**
     * Explicit stance an owner's soldiers hold toward the target, or null when
     * the built-in default rules apply. Server side only.
     */
    @Nullable
    public static FofStance resolveStance(MinecraftServer server, UUID ownerId, LivingEntity target) {
        OwnerFof fof = get(server).peek(ownerId);
        return fof != null ? fof.resolve(target) : null;
    }

    /**
     * Applies one stance change. A null stance clears the override (back to
     * default rules). Returns false when the key is invalid (self-mark,
     * unknown team, unknown category).
     */
    public static boolean applyStance(MinecraftServer server, UUID ownerId, FofTargetType type,
                                      @Nullable UUID playerKey, @Nullable String stringKey,
                                      @Nullable FofStance stance) {
        switch (type) {
            case PLAYER -> {
                if (playerKey == null || playerKey.equals(ownerId)) return false;
            }
            case TEAM -> {
                if (stringKey == null || server.getScoreboard().getPlayerTeam(stringKey) == null) return false;
            }
            case CATEGORY -> {
                if (stringKey == null || FofCategory.fromName(stringKey) == null) return false;
            }
        }
        FofSettings settings = get(server);
        OwnerFof fof = settings.owner(ownerId);
        switch (type) {
            case PLAYER -> fof.setPlayer(playerKey, stance);
            case TEAM -> fof.setTeam(stringKey, stance);
            case CATEGORY -> fof.setCategory(FofCategory.fromName(stringKey), stance);
        }
        settings.setDirty();
        return true;
    }

    public static FofSettings load(CompoundTag tag) {
        FofSettings settings = new FofSettings();
        ListTag list = tag.getList("Entries", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entryTag = list.getCompound(i);
            if (!entryTag.hasUUID("Owner")) continue;
            UUID ownerId = entryTag.getUUID("Owner");
            byte typeOrd = entryTag.contains("Type", Tag.TAG_ANY_NUMERIC) ? entryTag.getByte("Type") : -1;
            FofTargetType[] types = FofTargetType.values();
            if (typeOrd < 0 || typeOrd >= types.length) continue;
            FofStance stance = FofStance.fromOrdinal(entryTag.getInt("Stance"));
            if (stance == null) continue;
            OwnerFof fof = settings.owner(ownerId);
            switch (types[typeOrd]) {
                case PLAYER -> {
                    if (entryTag.hasUUID("Key")) fof.setPlayer(entryTag.getUUID("Key"), stance);
                }
                case TEAM -> {
                    String teamName = entryTag.getString("Key");
                    if (!teamName.isEmpty()) fof.setTeam(teamName, stance);
                }
                case CATEGORY -> fof.setCategory(FofCategory.fromName(entryTag.getString("Key")), stance);
            }
        }
        return settings;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag list = new ListTag();
        for (Map.Entry<UUID, OwnerFof> ownerEntry : owners.entrySet()) {
            UUID ownerId = ownerEntry.getKey();
            OwnerFof fof = ownerEntry.getValue();
            for (Map.Entry<UUID, FofStance> entry : fof.playerView().entrySet()) {
                CompoundTag entryTag = baseEntry(ownerId, FofTargetType.PLAYER, entry.getValue());
                entryTag.putUUID("Key", entry.getKey());
                list.add(entryTag);
            }
            for (Map.Entry<String, FofStance> entry : fof.teamView().entrySet()) {
                CompoundTag entryTag = baseEntry(ownerId, FofTargetType.TEAM, entry.getValue());
                entryTag.putString("Key", entry.getKey());
                list.add(entryTag);
            }
            for (Map.Entry<FofCategory, FofStance> entry : fof.categoryView().entrySet()) {
                CompoundTag entryTag = baseEntry(ownerId, FofTargetType.CATEGORY, entry.getValue());
                entryTag.putString("Key", entry.getKey().getSerializedName());
                list.add(entryTag);
            }
        }
        tag.put("Entries", list);
        return tag;
    }

    private static CompoundTag baseEntry(UUID ownerId, FofTargetType type, FofStance stance) {
        CompoundTag entryTag = new CompoundTag();
        entryTag.putUUID("Owner", ownerId);
        entryTag.putByte("Type", (byte) type.ordinal());
        entryTag.putInt("Stance", stance.ordinal());
        return entryTag;
    }

    /** One owner's override maps. Absent entries mean the default rules apply. */
    public static final class OwnerFof {
        private final Map<UUID, FofStance> players = new HashMap<>();
        private final Map<String, FofStance> teams = new HashMap<>();
        private final Map<FofCategory, FofStance> categories = new EnumMap<>(FofCategory.class);

        public void setPlayer(UUID playerKey, @Nullable FofStance stance) {
            mutate(players, playerKey, stance);
        }

        public void setTeam(String teamName, @Nullable FofStance stance) {
            mutate(teams, teamName, stance);
        }

        public void setCategory(@Nullable FofCategory category, @Nullable FofStance stance) {
            if (category != null) mutate(categories, category, stance);
        }

        private static <K> void mutate(Map<K, FofStance> map, K key, @Nullable FofStance stance) {
            if (stance == null) {
                map.remove(key);
            } else {
                map.put(key, stance);
            }
        }

        public Map<UUID, FofStance> playerView() {
            return Map.copyOf(players);
        }

        public Map<String, FofStance> teamView() {
            return Map.copyOf(teams);
        }

        public Map<FofCategory, FofStance> categoryView() {
            return Map.copyOf(categories);
        }

        @Nullable
        public FofStance resolveCategory(FofCategory category) {
            return categories.get(category);
        }

        public boolean isEmpty() {
            return players.isEmpty() && teams.isEmpty() && categories.isEmpty();
        }

        /** Explicit stance for the target, or null. Precedence: player > team > category. */
        @Nullable
        FofStance resolve(LivingEntity target) {
            UUID playerKey = null;
            if (target instanceof Player player) {
                playerKey = player.getUUID();
            } else if (target instanceof SoldierEntity soldier) {
                playerKey = soldier.getOwnerUUID().orElse(null);
            }
            if (playerKey != null) {
                FofStance stance = players.get(playerKey);
                if (stance != null) return stance;
            }

            String teamKey = null;
            if (target instanceof SoldierEntity soldier) {
                LivingEntity owner = soldier.getOwner();
                Team ownerTeam = owner != null ? owner.getTeam() : null;
                teamKey = ownerTeam != null ? ownerTeam.getName() : null;
            } else if (target.getTeam() != null) {
                teamKey = target.getTeam().getName();
            }
            if (teamKey != null) {
                FofStance stance = teams.get(teamKey);
                if (stance != null) return stance;
            }

            if (target instanceof Monster) return categories.get(FofCategory.MONSTERS);
            if (target instanceof TargetEntity) return categories.get(FofCategory.TARGET_DUMMIES);
            return null;
        }
    }
}
