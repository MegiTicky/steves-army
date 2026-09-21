package com.stevesarmy.network;

import com.stevesarmy.compat.PlayerReviveCompat;
import com.stevesarmy.compat.VS2Compat;
import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.entity.SoldierRole;
import com.stevesarmy.respawn.PlayerDeathHandler;
import com.stevesarmy.respawn.SoldierSwapManager;
import com.stevesarmy.squad.OwnedSoldierRegistry;
import com.stevesarmy.squad.SquadData;
import com.stevesarmy.squad.SquadManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkEvent;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Client → server request to live-swap with a squad soldier. Three modes:
 * the soldier under the crosshair (ENTITY), the nearest soldier of a chosen
 * role (ROLE, from the wheel), or the previous body (LAST, swap back).
 */
public class SwapSoldierPacket {
    private enum Mode { ENTITY, ROLE, LAST }

    /** Matches the client's cone-sweep look range (command-stick convention). */
    private static final double LOOK_SWAP_MAX_DISTANCE = 32.0;

    private final Mode mode;
    private final UUID soldierId;
    private final int roleOrdinal;

    public static SwapSoldierPacket byEntity(UUID soldierId) {
        return new SwapSoldierPacket(Mode.ENTITY, soldierId, -1);
    }

    public static SwapSoldierPacket byRole(SoldierRole role) {
        return new SwapSoldierPacket(Mode.ROLE, null, role.ordinal());
    }

    public static SwapSoldierPacket byLast() {
        return new SwapSoldierPacket(Mode.LAST, null, -1);
    }

    private SwapSoldierPacket(Mode mode, UUID soldierId, int roleOrdinal) {
        this.mode = mode;
        this.soldierId = soldierId;
        this.roleOrdinal = roleOrdinal;
    }

    public static void encode(SwapSoldierPacket message, FriendlyByteBuf buf) {
        buf.writeVarInt(message.mode.ordinal());
        buf.writeBoolean(message.soldierId != null);
        if (message.soldierId != null) buf.writeUUID(message.soldierId);
        buf.writeVarInt(message.roleOrdinal);
    }

    public static SwapSoldierPacket decode(FriendlyByteBuf buf) {
        int modeOrdinal = buf.readVarInt();
        Mode[] modes = Mode.values();
        if (modeOrdinal < 0 || modeOrdinal >= modes.length) return byLast();
        UUID soldierId = buf.readBoolean() ? buf.readUUID() : null;
        return new SwapSoldierPacket(modes[modeOrdinal], soldierId, buf.readVarInt());
    }

    public static void handle(SwapSoldierPacket message, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null || !player.isAlive()) return;

            if (PlayerReviveCompat.isLoaded() && PlayerReviveCompat.isPlayerBleeding(player)) {
                player.sendSystemMessage(Component.literal("You are downed — no swapping while bleeding out."));
                return;
            }

            ServerLevel level = player.serverLevel();
            SquadManager squadManager = SquadManager.get(level);
            Optional<SquadData> squadOpt = squadManager.getSquadByLeader(player.getUUID());
            if (squadOpt.isEmpty()) {
                player.sendSystemMessage(Component.literal("You have no squad to swap within."));
                return;
            }

            SoldierEntity target = resolveTarget(player, level, squadManager, squadOpt.get(), message);
            if (target == null) return;

            if (PlayerDeathHandler.isPendingRespawnTarget(target.getUUID())) {
                player.sendSystemMessage(Component.literal("That soldier is reserved for your respawn."));
                return;
            }
            if (target.isRecalling()) {
                player.sendSystemMessage(Component.literal("That soldier is being recalled."));
                return;
            }

            SoldierSwapManager.swap(player, target);
            NetworkHandler.sendTo(player, SquadStatusSyncPacket.createForPlayer(player));
        });
        context.setPacketHandled(true);
    }

    private static SoldierEntity resolveTarget(ServerPlayer player, ServerLevel level, SquadManager squadManager,
                                               SquadData squad, SwapSoldierPacket message) {
        switch (message.mode) {
            case ENTITY: {
                if (message.soldierId == null) return null;
                Entity entity = level.getEntity(message.soldierId);
                if (!(entity instanceof SoldierEntity soldier)) {
                    player.sendSystemMessage(Component.literal("That soldier is not loaded."));
                    return null;
                }
                if (!isOwnedSquadMember(player, squad, soldier)) {
                    player.sendSystemMessage(Component.literal("That soldier is not in your squad."));
                    return null;
                }
                Vec3 soldierPos = VS2Compat.getSoldierWorldPosition(soldier);
                if (soldierPos.distanceToSqr(player.getEyePosition()) > LOOK_SWAP_MAX_DISTANCE * LOOK_SWAP_MAX_DISTANCE) {
                    player.sendSystemMessage(Component.literal("Too far to swap by sight."));
                    return null;
                }
                return soldier;
            }
            case ROLE: {
                SoldierRole[] roles = SoldierRole.values();
                if (message.roleOrdinal < 0 || message.roleOrdinal >= roles.length) return null;
                SoldierRole wanted = roles[message.roleOrdinal];
                List<LivingEntity> members = squadManager.getSquadMembers(level, squad.getSquadId(), player.getUUID());
                SoldierEntity nearest = null;
                double nearestDist = Double.MAX_VALUE;
                for (LivingEntity member : members) {
                    if (!(member instanceof SoldierEntity soldier)) continue;
                    if (soldier.getRole() != wanted) continue;
                    if (!isOwnedSquadMember(player, squad, soldier)) continue;
                    double dist = VS2Compat.getSoldierWorldPosition(soldier).distanceToSqr(player.position());
                    if (dist < nearestDist) {
                        nearestDist = dist;
                        nearest = soldier;
                    }
                }
                if (nearest == null) {
                    player.sendSystemMessage(Component.literal("No loaded ")
                        .append(wanted.getDisplayName())
                        .append(Component.literal(" in your squad right now.")));
                }
                return nearest;
            }
            case LAST: {
                UUID lastSwapped = SoldierSwapManager.getLastSwapped(player);
                if (lastSwapped == null) {
                    player.sendSystemMessage(Component.literal("No previous body to swap back to."));
                    return null;
                }
                Entity entity = level.getEntity(lastSwapped);
                if (!(entity instanceof SoldierEntity soldier)) {
                    player.sendSystemMessage(Component.literal("Your previous body was lost."));
                    return null;
                }
                if (!isOwnedSquadMember(player, squad, soldier)) {
                    player.sendSystemMessage(Component.literal("Your previous body was lost."));
                    return null;
                }
                return soldier;
            }
            default:
                return null;
        }
    }

    private static boolean isOwnedSquadMember(ServerPlayer player, SquadData squad, SoldierEntity soldier) {
        if (!soldier.isAlive() || soldier.isRemoved()) return false;
        if (!soldier.isOwnedBy(player)) return false;
        if (OwnedSoldierRegistry.get(player.getServer()).get(soldier.getUUID()) == null) return false;
        return soldier.getSquadId() != null && soldier.getSquadId().equals(squad.getSquadId());
    }
}
