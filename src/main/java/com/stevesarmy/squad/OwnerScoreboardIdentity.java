package com.stevesarmy.squad;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.saveddata.SavedData;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Persist scoreboard entry identity, not team names: offline /team edits remain authoritative. */
public final class OwnerScoreboardIdentity extends SavedData {
    private static final String DATA_NAME = "steves_army_owner_scoreboard_identity";
    private final Map<UUID, String> names = new HashMap<>();

    public static OwnerScoreboardIdentity get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
            OwnerScoreboardIdentity::load, OwnerScoreboardIdentity::new, DATA_NAME);
    }

    public void remember(ServerPlayer player) {
        String name = player.getScoreboardName();
        if (!name.equals(names.put(player.getUUID(), name))) setDirty();
    }

    @Nullable
    public String name(UUID ownerId) {
        return names.get(ownerId);
    }

    public static OwnerScoreboardIdentity load(CompoundTag tag) {
        OwnerScoreboardIdentity data = new OwnerScoreboardIdentity();
        ListTag entries = tag.getList("Owners", Tag.TAG_COMPOUND);
        for (int i = 0; i < entries.size(); i++) {
            CompoundTag entry = entries.getCompound(i);
            String name = entry.getString("Name");
            if (entry.hasUUID("Owner") && !name.isEmpty()) {
                data.names.put(entry.getUUID("Owner"), name);
            }
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag entries = new ListTag();
        names.forEach((owner, name) -> {
            CompoundTag entry = new CompoundTag();
            entry.putUUID("Owner", owner);
            entry.putString("Name", name);
            entries.add(entry);
        });
        tag.put("Owners", entries);
        return tag;
    }
}
