package com.stevesarmy.squad;

import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.entity.SoldierRole;
import com.stevesarmy.inventory.SoldierInventory;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.SavedData;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Persistent owner/location index for soldiers that may be outside loaded chunks. */
public class OwnedSoldierRegistry extends SavedData {
    private static final String DATA_NAME = "steves_army_owned_soldiers";
    private final Map<UUID, Entry> entries = new HashMap<>();
    private final java.util.Set<UUID> dismissed = new java.util.HashSet<>();

    public static OwnedSoldierRegistry get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
            OwnedSoldierRegistry::load,
            OwnedSoldierRegistry::new,
            DATA_NAME);
    }

    public static OwnedSoldierRegistry load(CompoundTag tag) {
        OwnedSoldierRegistry registry = new OwnedSoldierRegistry();
        ListTag list = tag.getList("Soldiers", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            Entry entry = Entry.load(list.getCompound(i));
            if (entry != null) registry.entries.put(entry.soldierId, entry);
        }
        ListTag dismissedList = tag.getList("Dismissed", Tag.TAG_INT_ARRAY);
        for (int i = 0; i < dismissedList.size(); i++) {
            registry.dismissed.add(((net.minecraft.nbt.IntArrayTag) dismissedList.get(i)).getAsIntArray().length == 4
                ? net.minecraft.nbt.NbtUtils.loadUUID((net.minecraft.nbt.IntArrayTag) dismissedList.get(i)) : null);
        }
        registry.dismissed.remove(null);
        return registry;
    }

    public void refresh(SoldierEntity soldier, ServerLevel level) {
        UUID ownerId = soldier.getOwnerUUID().orElse(null);
        if (ownerId == null) return;
        Entry entry = entries.computeIfAbsent(soldier.getUUID(), ignored -> new Entry(soldier.getUUID()));
        entry.update(soldier, ownerId, level);
        setDirty();
    }

    public void remove(UUID soldierId) {
        if (entries.remove(soldierId) != null) setDirty();
    }

    public void dismiss(UUID soldierId) {
        entries.remove(soldierId);
        dismissed.add(soldierId);
        setDirty();
    }

    public boolean isDismissed(UUID soldierId) {
        return dismissed.contains(soldierId);
    }

    public void setRecallTicks(UUID soldierId, int ticks) {
        Entry entry = entries.get(soldierId);
        if (entry != null) {
            entry.recallTicks = Math.max(0, ticks);
            setDirty();
        }
    }

    public void setPendingRole(UUID soldierId, SoldierRole role) {
        Entry entry = entries.get(soldierId);
        if (entry != null) {
            entry.pendingRole = role.ordinal();
            setDirty();
        }
    }

    public void clearPendingRole(UUID soldierId) {
        Entry entry = entries.get(soldierId);
        if (entry != null && entry.pendingRole >= 0) {
            entry.pendingRole = -1;
            setDirty();
        }
    }

    @Nullable
    public Entry get(UUID soldierId) {
        return entries.get(soldierId);
    }

    public List<Entry> getOwned(UUID ownerId) {
        List<Entry> result = new ArrayList<>();
        for (Entry entry : entries.values()) {
            if (ownerId.equals(entry.ownerId)) result.add(entry);
        }
        result.sort(Comparator.comparing(entry -> entry.soldierId));
        return result;
    }

    /** Removes snapshots left behind by soldiers that died before the registry cleanup existed. */
    public void pruneDeadEntries() {
        if (entries.entrySet().removeIf(entry -> entry.getValue().health <= 0.0F)) {
            setDirty();
        }
    }

    public Collection<Entry> getAll() {
        return entries.values();
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag list = new ListTag();
        for (Entry entry : entries.values()) list.add(entry.save());
        tag.put("Soldiers", list);
        ListTag dismissedList = new ListTag();
        for (UUID soldierId : dismissed) dismissedList.add(net.minecraft.nbt.NbtUtils.createUUID(soldierId));
        tag.put("Dismissed", dismissedList);
        return tag;
    }

    public static final class Entry {
        private final UUID soldierId;
        private UUID ownerId;
        private String dimension = "minecraft:overworld";
        private BlockPos position = BlockPos.ZERO;
        private String name = "Soldier";
        private float health;
        private float maxHealth;
        private int totalAmmo;
        private ItemStack gunStack = ItemStack.EMPTY;
        private int squadMode;
        private int fireDiscipline;
        private int fireTeam;
        private int role;
        private int pendingRole = -1;
        private int coverState;
        private int recallTicks;

        private Entry(UUID soldierId) {
            this.soldierId = soldierId;
        }

        private void update(SoldierEntity soldier, UUID ownerId, ServerLevel level) {
            this.ownerId = ownerId;
            this.dimension = level.dimension().location().toString();
            this.position = soldier.blockPosition().immutable();
            this.name = soldier.getName().getString();
            this.health = soldier.getHealth();
            this.maxHealth = soldier.getMaxHealth();
            this.totalAmmo = soldier.getCombatGoal() != null ? soldier.getCombatGoal().getTotalAmmo() : 0;
            this.gunStack = soldier.getSoldierInventory().getItem(SoldierInventory.SLOT_MAIN_HAND).copy();
            this.squadMode = soldier.getSquadMode().ordinal();
            this.fireDiscipline = soldier.getFireDiscipline().ordinal();
            this.fireTeam = soldier.getFireTeam().ordinal();
            this.role = soldier.getRole().ordinal();
            this.coverState = soldier.getSyncedCoverState();
            this.recallTicks = soldier.getRecallTicks();
        }

        private static Entry load(CompoundTag tag) {
            if (!tag.hasUUID("Soldier") || !tag.hasUUID("Owner")) return null;
            Entry entry = new Entry(tag.getUUID("Soldier"));
            entry.ownerId = tag.getUUID("Owner");
            entry.dimension = tag.getString("Dimension");
            entry.position = BlockPos.of(tag.getLong("Position"));
            entry.name = tag.getString("Name");
            entry.health = tag.getFloat("Health");
            entry.maxHealth = tag.getFloat("MaxHealth");
            entry.totalAmmo = tag.getInt("Ammo");
            if (tag.contains("Gun", Tag.TAG_COMPOUND)) entry.gunStack = ItemStack.of(tag.getCompound("Gun"));
            entry.squadMode = tag.getInt("SquadMode");
            entry.fireDiscipline = tag.getInt("FireDiscipline");
            entry.fireTeam = tag.getInt("FireTeam");
            entry.role = tag.contains("Role") ? tag.getInt("Role") : 0;
            entry.pendingRole = tag.contains("PendingRole") ? tag.getInt("PendingRole") : -1;
            entry.coverState = tag.getInt("CoverState");
            entry.recallTicks = tag.getInt("RecallTicks");
            return entry;
        }

        private CompoundTag save() {
            CompoundTag tag = new CompoundTag();
            tag.putUUID("Soldier", soldierId);
            tag.putUUID("Owner", ownerId);
            tag.putString("Dimension", dimension);
            tag.putLong("Position", position.asLong());
            tag.putString("Name", name);
            tag.putFloat("Health", health);
            tag.putFloat("MaxHealth", maxHealth);
            tag.putInt("Ammo", totalAmmo);
            if (!gunStack.isEmpty()) tag.put("Gun", gunStack.save(new CompoundTag()));
            tag.putInt("SquadMode", squadMode);
            tag.putInt("FireDiscipline", fireDiscipline);
            tag.putInt("FireTeam", fireTeam);
            tag.putInt("Role", role);
            if (pendingRole >= 0) tag.putInt("PendingRole", pendingRole);
            tag.putInt("CoverState", coverState);
            tag.putInt("RecallTicks", recallTicks);
            return tag;
        }

        public UUID soldierId() { return soldierId; }
        public UUID ownerId() { return ownerId; }
        public String dimension() { return dimension; }
        public BlockPos position() { return position; }
        public String name() { return name; }
        public float health() { return health; }
        public float maxHealth() { return maxHealth; }
        public int totalAmmo() { return totalAmmo; }
        public ItemStack gunStack() { return gunStack.copy(); }
        public int squadMode() { return squadMode; }
        public int fireDiscipline() { return fireDiscipline; }
        public int fireTeam() { return fireTeam; }
        public int role() { return role; }
        public int pendingRole() { return pendingRole; }
        public int coverState() { return coverState; }
        public int recallTicks() { return recallTicks; }
    }
}
