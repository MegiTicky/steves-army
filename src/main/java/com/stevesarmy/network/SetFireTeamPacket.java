package com.stevesarmy.network;

import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.squad.FireTeam;
import com.stevesarmy.squad.FireTeamAssignment;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

public class SetFireTeamPacket {
    private final Action action;
    private final int teamCount;
    private final UUID soldierId;
    private final FireTeam targetTeam;

    public enum Action {
        SET_TEAM_COUNT,
        ASSIGN_SOLDIER,
        REBALANCE
    }

    private SetFireTeamPacket(Action action, int teamCount, UUID soldierId, FireTeam targetTeam) {
        this.action = action;
        this.teamCount = teamCount;
        this.soldierId = soldierId;
        this.targetTeam = targetTeam;
    }

    public static SetFireTeamPacket setTeamCount(int count) {
        return new SetFireTeamPacket(Action.SET_TEAM_COUNT, count, null, null);
    }

    public static SetFireTeamPacket assignSoldier(UUID soldierId, FireTeam team) {
        return new SetFireTeamPacket(Action.ASSIGN_SOLDIER, 0, soldierId, team);
    }

    public static SetFireTeamPacket rebalance() {
        return new SetFireTeamPacket(Action.REBALANCE, 0, null, null);
    }

    public static void encode(SetFireTeamPacket msg, FriendlyByteBuf buf) {
        buf.writeEnum(msg.action);
        buf.writeInt(msg.teamCount);
        buf.writeBoolean(msg.soldierId != null);
        if (msg.soldierId != null) {
            buf.writeUUID(msg.soldierId);
            buf.writeEnum(msg.targetTeam);
        }
    }

    public static SetFireTeamPacket decode(FriendlyByteBuf buf) {
        Action action = buf.readEnum(Action.class);
        int teamCount = buf.readInt();
        boolean hasSoldier = buf.readBoolean();
        UUID soldierId = null;
        FireTeam targetTeam = null;
        if (hasSoldier) {
            soldierId = buf.readUUID();
            targetTeam = buf.readEnum(FireTeam.class);
        }
        return new SetFireTeamPacket(action, teamCount, soldierId, targetTeam);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;
            if (!(player.level() instanceof ServerLevel serverLevel)) return;

            FireTeamAssignment fta = FireTeamAssignment.get(serverLevel, player.getUUID());

            switch (action) {
                case SET_TEAM_COUNT -> {
                    fta.setTeamCount(teamCount);
                }
                case ASSIGN_SOLDIER -> {
                    fta.assignToTeam(soldierId, targetTeam);
                    Entity entity = serverLevel.getEntity(soldierId);
                    if (entity instanceof SoldierEntity s && s.isOwnedBy(player)) {
                        s.setFireTeam(targetTeam);
                    }
                }
                case REBALANCE -> {
                    // Include every loaded owned soldier, not only those near the player.
                    List<SoldierEntity> ownedSoldiers = new ArrayList<>();
                    for (Entity entity : serverLevel.getEntities().getAll()) {
                        if (entity instanceof SoldierEntity soldier && soldier.isOwnedBy(player)) {
                            ownedSoldiers.add(soldier);
                        }
                    }
                    fta.rebalance(ownedSoldiers, player.position());
                    for (SoldierEntity soldier : ownedSoldiers) {
                        soldier.setFireTeam(fta.getTeamFor(soldier.getUUID()));
                    }
                }
            }
            NetworkHandler.sendTo(player, new FireTeamScopeSyncPacket(fta.getTeamCount()));
            NetworkHandler.sendTo(player, SquadStatusSyncPacket.createForPlayer(player));
        });
        ctx.get().setPacketHandled(true);
    }
}
