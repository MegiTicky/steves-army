package com.stevesarmy.network;

import com.stevesarmy.combat.RecallRequestManager;
import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.entity.SoldierRole;
import com.stevesarmy.entity.SoldierRoleHandler;
import com.stevesarmy.squad.FireTeam;
import com.stevesarmy.squad.FireTeamAssignment;
import com.stevesarmy.squad.OwnedSoldierRegistry;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Bulk conversion between maneuver squads and garrisons.
 * <p>TO_GARRISON turns every owned soldier currently on the given fire team into a
 * garrison (player-owned, holds in place, ignores pings). TO_SQUAD turns every owned
 * garrison back into a rifleman on the given fire team and recalls them to the player.
 * Only loaded soldiers are converted; unloaded ones are left for a later pass.
 */
public class BulkGarrisonPacket {
    public enum Mode {
        TO_GARRISON,
        TO_SQUAD
    }

    private final int modeOrdinal;
    private final int targetTeamOrdinal;

    public BulkGarrisonPacket(Mode mode, FireTeam targetTeam) {
        this.modeOrdinal = mode.ordinal();
        this.targetTeamOrdinal = targetTeam.ordinal();
    }

    private BulkGarrisonPacket(int modeOrdinal, int targetTeamOrdinal) {
        this.modeOrdinal = modeOrdinal;
        this.targetTeamOrdinal = targetTeamOrdinal;
    }

    public static void encode(BulkGarrisonPacket message, FriendlyByteBuf buffer) {
        buffer.writeInt(message.modeOrdinal);
        buffer.writeInt(message.targetTeamOrdinal);
    }

    public static BulkGarrisonPacket decode(FriendlyByteBuf buffer) {
        return new BulkGarrisonPacket(buffer.readInt(), buffer.readInt());
    }

    public static void handle(BulkGarrisonPacket message, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null) {
                return;
            }

            Mode[] modes = Mode.values();
            if (message.modeOrdinal < 0 || message.modeOrdinal >= modes.length) {
                return;
            }
            FireTeam[] teams = FireTeam.values();
            if (message.targetTeamOrdinal < 0 || message.targetTeamOrdinal >= teams.length) {
                return;
            }
            FireTeam targetTeam = teams[message.targetTeamOrdinal];

            OwnedSoldierRegistry registry = OwnedSoldierRegistry.get(player.getServer());
            List<OwnedSoldierRegistry.Entry> entries = registry.getOwned(player.getUUID());

            List<UUID> toRecall = new ArrayList<>();
            switch (modes[message.modeOrdinal]) {
                case TO_GARRISON -> {
                    if (targetTeam == FireTeam.ALL || targetTeam == FireTeam.GARRISON) {
                        return;
                    }
                    for (OwnedSoldierRegistry.Entry entry : entries) {
                        if (entry.fireTeam() != targetTeam.ordinal()) continue;
                        SoldierEntity soldier = findLoaded(player, entry.soldierId());
                        if (soldier == null) continue;
                        SoldierRoleHandler.convertSoldier(soldier, SoldierRole.GARRISON, FireTeam.GARRISON);
                    }
                }
                case TO_SQUAD -> {
                    if (targetTeam == FireTeam.GARRISON) {
                        return;
                    }
                    FireTeam resolvedTarget = targetTeam == FireTeam.ALL
                        ? FireTeamAssignment.get(player.getServer().overworld(), player.getUUID()).getActiveTeams().get(0)
                        : targetTeam;
                    for (OwnedSoldierRegistry.Entry entry : entries) {
                        if (entry.fireTeam() != FireTeam.GARRISON.ordinal()) continue;
                        SoldierEntity soldier = findLoaded(player, entry.soldierId());
                        if (soldier == null) continue;
                        SoldierEntity replacement = SoldierRoleHandler.convertSoldier(
                            soldier, SoldierRole.RIFLEMAN, resolvedTarget);
                        if (replacement != null) {
                            toRecall.add(replacement.getUUID());
                        }
                    }
                }
            }

            for (UUID soldierId : toRecall) {
                RecallRequestManager.start(player, soldierId);
            }
        });
        context.setPacketHandled(true);
    }

    private static SoldierEntity findLoaded(ServerPlayer player, UUID soldierId) {
        for (ServerLevel level : player.getServer().getAllLevels()) {
            Entity entity = level.getEntity(soldierId);
            if (entity instanceof SoldierEntity soldier) {
                return soldier;
            }
        }
        return null;
    }
}
