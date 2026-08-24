package com.stevesarmy.entity;

import com.stevesarmy.inventory.SoldierInventory;
import com.stevesarmy.squad.FireTeamAssignment;
import com.stevesarmy.squad.SquadData;
import com.stevesarmy.squad.SquadManager;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/** Shared server-side soldier creation and ownership initialization. */
public final class SoldierSpawner {
    private SoldierSpawner() {}

    public static SpawnResult spawn(
        ServerLevel level,
        EntityType<? extends SoldierEntity> entityType,
        Player owner,
        Vec3 position,
        float yaw,
        float pitch,
        CompoundTag loadout
    ) {
        Optional<String> validation = validateLoadout(loadout);
        if (validation.isPresent()) {
            return SpawnResult.failure(validation.get());
        }

        SoldierEntity soldier = entityType.create(level);
        if (soldier == null) {
            return SpawnResult.failure("Failed to create entity " + entityType);
        }

        soldier.moveTo(position.x, position.y, position.z, yaw, pitch);
        if (loadout != null) {
            applyLoadout(soldier, loadout);
        }

        return finishSpawn(level, soldier, owner, owner != null);
    }

    /**
     * Finishes the same owner, fire-team, squad, persistence, and world insertion
     * sequence used by command spawning and the normal soldier spawn egg.
     */
    public static SpawnResult finishSpawn(ServerLevel level, SoldierEntity soldier, Player owner, boolean forceOwner) {
        if (owner != null && (forceOwner || soldier.getOwnerUUID().isEmpty())) {
            soldier.setOwnerUUID(owner.getUUID());
        }

        if (owner != null && soldier.isOwnedBy(owner)) {
            if (soldier instanceof GarrisonEntity) {
                // Garrisons are never part of the A/B/C/D fire-team buckets.
                soldier.setFireTeam(com.stevesarmy.squad.FireTeam.GARRISON);
            } else {
                FireTeamAssignment fireTeams = FireTeamAssignment.get(level, owner.getUUID());
                soldier.setFireTeam(fireTeams.getSelectedSpawnTeam());
                fireTeams.assignToTeam(soldier.getUUID(), soldier.getFireTeam());
            }
        }

        soldier.setPersistenceRequired();
        level.addFreshEntity(soldier);

        if (owner != null && soldier.isOwnedBy(owner)) {
            SquadManager squadManager = SquadManager.get(level);
            SquadData squad = squadManager.getSquadByLeader(owner.getUUID())
                .orElseGet(() -> squadManager.createSquad(owner.getUUID()));
            soldier.setSquadId(squad.getSquadId());
            if (!squadManager.addMemberToSquad(squad.getSquadId(), soldier.getUUID())) {
                return SpawnResult.failure("Soldier spawned, but could not be added to the owner's squad");
            }
        }

        return SpawnResult.success(soldier);
    }

    public static CompoundTag saveLoadout(SoldierEntity soldier) {
        soldier.getSoldierInventory().syncFromEntity(soldier);
        return soldier.getSoldierInventory().save();
    }

    public static Optional<String> validateLoadout(CompoundTag loadout) {
        if (loadout == null) {
            return Optional.empty();
        }
        if (!loadout.contains("Items", Tag.TAG_LIST)) {
            return Optional.of("Loadout must contain an Items list");
        }

        ListTag items = loadout.getList("Items", Tag.TAG_COMPOUND);
        Set<Integer> slots = new HashSet<>();
        for (int i = 0; i < items.size(); i++) {
            CompoundTag itemTag = items.getCompound(i);
            int slot = itemTag.getInt("Slot");
            if (slot < 0 || slot >= SoldierInventory.INVENTORY_SIZE) {
                return Optional.of("Loadout item " + i + " has invalid slot " + slot);
            }
            if (!slots.add(slot)) {
                return Optional.of("Loadout contains duplicate slot " + slot);
            }

            String itemId = itemTag.getString("id");
            ResourceLocation resourceLocation = ResourceLocation.tryParse(itemId);
            if (resourceLocation == null) {
                return Optional.of("Loadout item " + i + " has invalid item id: " + itemId);
            }
            Item item = ForgeRegistries.ITEMS.getValue(resourceLocation);
            if (item == null || item == net.minecraft.world.item.Items.AIR) {
                return Optional.of("Loadout item is unavailable on this server: " + itemId);
            }

            ItemStack stack = ItemStack.of(itemTag);
            if (stack.isEmpty()) {
                return Optional.of("Loadout item " + i + " could not be decoded: " + itemId);
            }
        }
        return Optional.empty();
    }

    private static void applyLoadout(SoldierEntity soldier, CompoundTag loadout) {
        SoldierInventory inventory = soldier.getSoldierInventory();
        inventory.load(loadout);
        inventory.syncArmorToEntity(soldier);
    }

    public record SpawnResult(boolean success, SoldierEntity soldier, String message) {
        public static SpawnResult success(SoldierEntity soldier) {
            return new SpawnResult(true, soldier, "");
        }

        public static SpawnResult failure(String message) {
            return new SpawnResult(false, null, message);
        }
    }
}
