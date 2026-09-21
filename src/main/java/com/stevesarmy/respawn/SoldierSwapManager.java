package com.stevesarmy.respawn;

import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.combat.GunIntegration;
import com.stevesarmy.combat.SoldierWeaponSelector;
import com.stevesarmy.combat.cover.CoverReservationManager;
import com.stevesarmy.compat.VS2Compat;
import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.entity.SoldierRole;
import com.stevesarmy.entity.SoldierRoleHandler;
import com.stevesarmy.entity.SoldierSpawner;
import com.stevesarmy.inventory.SoldierInventory;
import com.stevesarmy.squad.FireTeamAssignment;
import com.stevesarmy.squad.SquadData;
import com.stevesarmy.squad.SquadManager;
import com.stevesarmy.squad.SwapStashStore;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Live player ⇄ soldier body swap. The player takes the target soldier's
 * position, seat, and loadout; a soldier of the player's stored role spawns
 * where the player stood, carrying the player's items (overflow rides in
 * {@link SwapStashStore} bound to that soldier). Mirrors the death-respawn
 * takeover in {@link SoldierRespawnManager} but without dying.
 */
public final class SoldierSwapManager {
    /** What the player currently "is" — the role of the body he last swapped into (default RIFLEMAN). */
    public static final String STORED_ROLE_TAG = "steves_army_swap_role";
    /** The left-behind soldier of the most recent swap — the "swap back" target. */
    public static final String LAST_SWAPPED_TAG = "steves_army_swap_last";

    private static final double TELEPORT_OFFSET = 0.5;

    private SoldierSwapManager() {}

    public static SoldierRole getStoredRole(ServerPlayer player) {
        int ordinal = player.getPersistentData().getInt(STORED_ROLE_TAG);
        SoldierRole[] roles = SoldierRole.values();
        if (ordinal < 0 || ordinal >= roles.length) return SoldierRole.RIFLEMAN;
        return roles[ordinal];
    }

    public static void setStoredRole(ServerPlayer player, SoldierRole role) {
        player.getPersistentData().putInt(STORED_ROLE_TAG, role.ordinal());
    }

    public static UUID getLastSwapped(ServerPlayer player) {
        CompoundTag data = player.getPersistentData();
        return data.hasUUID(LAST_SWAPPED_TAG) ? data.getUUID(LAST_SWAPPED_TAG) : null;
    }

    public static void setLastSwapped(ServerPlayer player, UUID soldierId) {
        player.getPersistentData().putUUID(LAST_SWAPPED_TAG, soldierId);
    }

    public static void clearLastSwapped(ServerPlayer player) {
        player.getPersistentData().remove(LAST_SWAPPED_TAG);
    }

    /**
     * Performs the swap. The caller has validated target ownership, squad
     * membership, and liveness; this executes unconditionally on trust.
     *
     * @return true when the swap completed.
     */
    public static boolean swap(ServerPlayer player, SoldierEntity target) {
        ServerLevel level = player.serverLevel();
        SquadManager squadManager = SquadManager.get(level);
        Optional<SquadData> squadOpt = squadManager.getSquadByLeader(player.getUUID());
        if (squadOpt.isEmpty()) {
            player.sendSystemMessage(Component.literal("You have no squad to swap within."));
            return false;
        }
        SquadData squad = squadOpt.get();
        if (!squad.getMemberIds().contains(target.getUUID())
            || target.getSquadId() == null || !target.getSquadId().equals(squad.getSquadId())) {
            player.sendSystemMessage(Component.literal("That soldier is not in your squad."));
            return false;
        }
        if (PlayerDeathHandler.isPendingRespawnTarget(target.getUUID())) {
            player.sendSystemMessage(Component.literal("That soldier is reserved for your respawn."));
            return false;
        }
        if (target.isRecalling()) {
            player.sendSystemMessage(Component.literal("That soldier is being recalled."));
            return false;
        }

        // Snapshot the target's seat before the discard destroys transport state,
        // exactly like the death-respawn takeover.
        VS2Compat.SeatCapture seatCapture = VS2Compat.captureSeatForTakeover(level, target);
        Vec3 targetPos = VS2Compat.getSoldierWorldPosition(target);
        float targetYRot = target.getYRot();
        float targetXRot = target.getXRot();
        SoldierRole targetRole = target.getRole();

        SoldierRole leftBehindRole = getStoredRole(player);
        if (leftBehindRole == SoldierRole.VEHICLE_CREW && !VS2Compat.isEnabled()) {
            leftBehindRole = SoldierRole.RIFLEMAN;
        }

        // Spawn the left-behind body BEFORE the player's inventory is cleared —
        // it is filled from the player's items.
        SoldierEntity leftBehind = createLeftBehind(player, leftBehindRole);
        if (leftBehind == null) {
            player.sendSystemMessage(Component.literal("Swap failed: could not spawn your replacement body."));
            return false;
        }

        setLastSwapped(player, leftBehind.getUUID());
        SwapStashStore stashStore = SwapStashStore.get(player.getServer());
        String leftBehindRoleName = leftBehindRole.getDisplayName().getString();

        // Dress the player in the target's gear (clears the player inventory first).
        SoldierRespawnManager.transferEquipment(player, target, target.getSoldierInventory());

        // Remove the target and hand his spot over.
        target.stopRiding();
        CoverReservationManager.releaseAll(target);
        squadManager.removeMemberFromSquad(target.getUUID());
        FireTeamAssignment.get(player.getServer().overworld(), player.getUUID()).removeSoldier(target.getUUID());
        target.discard();

        teleportPlayerToSoldier(player, seatCapture, targetPos, targetYRot, targetXRot);

        // The player now "is" the swapped-in role.
        setStoredRole(player, targetRole);

        // Swap-back: the target may have been holding our overflow from an
        // earlier swap — return it to the now-freed player inventory.
        List<ItemStack> recovered = stashStore.take(player.getUUID(), target.getUUID());
        int recoveredCount = 0;
        for (ItemStack stack : recovered) {
            if (!player.getInventory().add(stack)) {
                player.drop(stack, false);
            } else {
                recoveredCount++;
            }
        }

        StringBuilder feedback = new StringBuilder("Swapped with ").append(target.getName().getString())
            .append(" (").append(targetRole.getDisplayName().getString()).append(") — your ")
            .append(leftBehindRoleName).append(" body took your place");
        if (recoveredCount > 0) {
            feedback.append(" and recovered ").append(recoveredCount).append(" stash stack(s)");
        }
        feedback.append('.');
        player.sendSystemMessage(Component.literal(feedback.toString()));

        StevesArmyMod.LOGGER.info("[Swap] Player {} swapped with soldier {} ({}); left-behind {} as {}",
            player.getName().getString(),
            target.getUUID().toString().substring(0, 8),
            targetRole,
            leftBehind.getUUID().toString().substring(0, 8),
            leftBehindRole);
        return true;
    }

    /** Spawns the replacement body at the player's position, filled with the player's items. */
    private static SoldierEntity createLeftBehind(ServerPlayer player, SoldierRole role) {
        ServerLevel level = player.serverLevel();
        EntityType<? extends SoldierEntity> type = SoldierRoleHandler.getEntityType(role);
        SoldierEntity leftBehind = type.create(level);
        if (leftBehind == null) return null;

        leftBehind.moveTo(player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot());
        leftBehind.maybeRandomizeSkin();

        List<ItemStack> overflow = fillFromPlayerInventory(leftBehind, player);

        // Owner, fire team, squad join, persistence, generated name.
        SoldierSpawner.SpawnResult result = SoldierSpawner.finishSpawn(level, leftBehind, player, true);
        if (!result.success()) {
            StevesArmyMod.LOGGER.warn("[Swap] Left-behind spawn failed: {}", result.message());
            leftBehind.discard();
            return null;
        }
        if (!overflow.isEmpty()) {
            SwapStashStore.get(player.getServer()).stash(player.getUUID(), leftBehind.getUUID(), overflow);
        }
        return leftBehind;
    }

    /**
     * Player → SoldierInventory mapping: armor mapped per-piece (vanilla's
     * armor list is boots→helmet, soldier slots are head→feet), main hand →
     * main hand, gun offhand → sidearm slot (the slot refuses non-guns),
     * everything else into the 20 general slots. Whatever does not fit is
     * returned as overflow for the stash instead of being dropped. Guns are
     * then re-slotted into the standard layout (main gun in hand, pistol in
     * the sidearm slot).
     */
    private static List<ItemStack> fillFromPlayerInventory(SoldierEntity leftBehind, ServerPlayer player) {
        Inventory playerInv = player.getInventory();
        SoldierInventory inv = leftBehind.getSoldierInventory();
        List<ItemStack> overflow = new ArrayList<>();

        for (int i = 0; i < 4; i++) {
            ItemStack armor = playerInv.armor.get(3 - i);
            if (!armor.isEmpty()) {
                inv.setItem(SoldierInventory.ARMOR_HEAD + i, armor.copy());
            }
        }

        ItemStack mainHand = playerInv.items.get(playerInv.selected);
        if (!mainHand.isEmpty()) {
            inv.setItem(SoldierInventory.SLOT_MAIN_HAND, mainHand.copy());
        }

        List<ItemStack> loose = new ArrayList<>();
        ItemStack offhand = playerInv.offhand.get(0);
        if (!offhand.isEmpty()) {
            if (GunIntegration.isGun(offhand)) {
                inv.setItem(SoldierInventory.SLOT_SIDEARM, offhand.copy());
            } else {
                loose.add(offhand.copy());
            }
        }
        for (int i = 0; i < playerInv.items.size(); i++) {
            if (i == playerInv.selected) continue;
            ItemStack stack = playerInv.items.get(i);
            if (!stack.isEmpty()) loose.add(stack.copy());
        }

        int cursor = SoldierInventory.SLOT_GENERAL_START;
        for (ItemStack stack : loose) {
            if (cursor < SoldierInventory.INVENTORY_SIZE) {
                inv.setItem(cursor++, stack);
            } else {
                overflow.add(stack);
            }
        }

        inv.syncArmorToEntity(leftBehind);
        SoldierWeaponSelector.normalizeGunSlots(leftBehind);
        return overflow;
    }

    private static void teleportPlayerToSoldier(ServerPlayer player, VS2Compat.SeatCapture seatCapture,
                                                Vec3 soldierPos, float yRot, float xRot) {
        Vec3 targetPos = seatCapture != null && VS2Compat.isWorldPlausible(seatCapture.worldPos())
            ? seatCapture.worldPos()
            : soldierPos;
        if (VS2Compat.isWorldPlausible(targetPos)) {
            player.stopRiding();
            player.teleportTo(targetPos.x, targetPos.y + TELEPORT_OFFSET, targetPos.z);
            player.setYRot(yRot);
            player.setXRot(xRot);
            player.setYHeadRot(yRot);
        } else {
            StevesArmyMod.LOGGER.warn("[Swap] No world-plausible position for swap target — player {} stays put",
                player.getName().getString());
        }

        if (seatCapture != null && seatCapture.hasSeat()
            && !VS2Compat.seatPlayerInCapturedSeat(player.serverLevel(), player, seatCapture)) {
            player.sendSystemMessage(Component.literal(
                "The crew seat was lost — you took the swap beside the vehicle instead."));
        }
    }
}
