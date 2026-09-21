package com.stevesarmy.squad;

import com.stevesarmy.StevesArmyMod;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.server.MinecraftServer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Overflow items that do not fit a soldier body during a role swap ("old body is
 * your backpack"). Stashes are keyed by the owning player and the left-behind
 * soldier holding them; they return to the player when he swaps back into that
 * body and are lost where the soldier is lost — consistent with the mod's
 * existing no-drop soldier death doctrine.
 */
public class SwapStashStore extends SavedData {
    private static final String DATA_NAME = "steves_army_swap_stash";
    /** Soldier inventories hold 26 slots vs the player's 37 non-armor stacks — overflow can never exceed 16. */
    public static final int MAX_OVERFLOW_STACKS = 16;

    private final Map<UUID, Map<UUID, List<ItemStack>>> stashesByPlayer = new HashMap<>();

    public static SwapStashStore get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
            SwapStashStore::load, SwapStashStore::new, DATA_NAME);
    }

    /** Binds the overflow stacks to the left-behind soldier, replacing any previous stash for him. */
    public void stash(UUID playerId, UUID soldierId, List<ItemStack> overflow) {
        if (overflow.isEmpty()) return;
        stashesByPlayer.computeIfAbsent(playerId, id -> new HashMap<>())
            .put(soldierId, new ArrayList<>(overflow));
        setDirty();
    }

    /** Removes and returns the stash the player left with the given soldier (swap-back). */
    public List<ItemStack> take(UUID playerId, UUID soldierId) {
        Map<UUID, List<ItemStack>> bySoldier = stashesByPlayer.get(playerId);
        if (bySoldier == null) return List.of();
        List<ItemStack> stash = bySoldier.remove(soldierId);
        if (bySoldier.isEmpty()) stashesByPlayer.remove(playerId);
        if (stash != null) setDirty();
        return stash != null ? stash : List.of();
    }

    /** Role conversion replaces the soldier entity — the stash must follow the new UUID. */
    public void rekey(UUID oldSoldierId, UUID newSoldierId) {
        boolean dirty = false;
        for (Map<UUID, List<ItemStack>> bySoldier : stashesByPlayer.values()) {
            List<ItemStack> stash = bySoldier.remove(oldSoldierId);
            if (stash != null) {
                bySoldier.put(newSoldierId, stash);
                dirty = true;
            }
        }
        if (dirty) setDirty();
    }

    /** The bound soldier died or was dismissed — his cargo goes with him. */
    public void pruneSoldier(UUID soldierId) {
        boolean dirty = false;
        for (Map<UUID, List<ItemStack>> bySoldier : stashesByPlayer.values()) {
            if (bySoldier.remove(soldierId) != null) dirty = true;
        }
        stashesByPlayer.values().removeIf(Map::isEmpty);
        if (dirty) setDirty();
    }

    public void prunePlayer(UUID playerId) {
        if (stashesByPlayer.remove(playerId) != null) setDirty();
    }

    public static SwapStashStore load(CompoundTag tag) {
        SwapStashStore store = new SwapStashStore();
        ListTag stashes = tag.getList("Stashes", Tag.TAG_COMPOUND);
        for (int i = 0; i < stashes.size(); i++) {
            CompoundTag entry = stashes.getCompound(i);
            UUID playerId = entry.getUUID("Owner");
            UUID soldierId = entry.getUUID("Soldier");
            ListTag items = entry.getList("Items", Tag.TAG_COMPOUND);
            List<ItemStack> stacks = new ArrayList<>(items.size());
            for (int j = 0; j < items.size() && stacks.size() < MAX_OVERFLOW_STACKS; j++) {
                ItemStack stack = ItemStack.of(items.getCompound(j));
                if (!stack.isEmpty()) stacks.add(stack);
            }
            if (!stacks.isEmpty()) {
                store.stashesByPlayer.computeIfAbsent(playerId, id -> new HashMap<>()).put(soldierId, stacks);
            }
        }
        return store;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag stashes = new ListTag();
        for (Map.Entry<UUID, Map<UUID, List<ItemStack>>> playerEntry : stashesByPlayer.entrySet()) {
            for (Map.Entry<UUID, List<ItemStack>> soldierEntry : playerEntry.getValue().entrySet()) {
                CompoundTag entry = new CompoundTag();
                entry.putUUID("Owner", playerEntry.getKey());
                entry.putUUID("Soldier", soldierEntry.getKey());
                ListTag items = new ListTag();
                for (ItemStack stack : soldierEntry.getValue()) {
                    if (!stack.isEmpty()) items.add(stack.save(new CompoundTag()));
                }
                entry.put("Items", items);
                stashes.add(entry);
            }
        }
        tag.put("Stashes", stashes);
        StevesArmyMod.LOGGER.debug("[SwapStash] Saved {} stash entries", stashes.size());
        return tag;
    }
}
