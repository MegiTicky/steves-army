package com.stevesarmy.network;

import com.stevesarmy.squad.FofCategory;
import com.stevesarmy.squad.FofSettings;
import com.stevesarmy.squad.FofRelations;
import com.stevesarmy.squad.FofStance;
import com.stevesarmy.squad.FofTargetType;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import javax.annotation.Nullable;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Sets one FoF stance override for the sending player's squad. A null stance
 * clears the override. Multi-player writes (event setups) go through
 * /stevesarmy fof; this packet only ever touches the sender's own map.
 */
public class SetFofStancePacket {
    private final FofTargetType targetType;
    private final UUID playerKey;
    private final String stringKey;
    private final FofStance stance;
    private Action action = Action.SET;
    private enum Action { SET, ACCEPT, CANCEL, SYNC }

    public static SetFofStancePacket sync() {
        SetFofStancePacket packet = playerStance(new UUID(0, 0), null);
        packet.action = Action.SYNC;
        return packet;
    }

    public static SetFofStancePacket accept(UUID playerKey, @Nullable FofStance expected) {
        SetFofStancePacket packet = playerStance(playerKey, expected);
        packet.action = Action.ACCEPT;
        return packet;
    }

    public static SetFofStancePacket cancel(UUID playerKey) {
        SetFofStancePacket packet = playerStance(playerKey, null);
        packet.action = Action.CANCEL;
        return packet;
    }

    public static SetFofStancePacket playerStance(UUID playerKey, @Nullable FofStance stance) {
        return new SetFofStancePacket(FofTargetType.PLAYER, playerKey, null, stance);
    }

    public static SetFofStancePacket teamStance(String teamName, @Nullable FofStance stance) {
        return new SetFofStancePacket(FofTargetType.TEAM, null, teamName, stance);
    }

    public static SetFofStancePacket categoryStance(FofCategory category, @Nullable FofStance stance) {
        return new SetFofStancePacket(FofTargetType.CATEGORY, null, category.getSerializedName(), stance);
    }

    private SetFofStancePacket(FofTargetType targetType, @Nullable UUID playerKey,
                               @Nullable String stringKey, @Nullable FofStance stance) {
        this.targetType = targetType;
        this.playerKey = playerKey;
        this.stringKey = stringKey;
        this.stance = stance;
    }

    public static void encode(SetFofStancePacket msg, FriendlyByteBuf buf) {
        buf.writeByte(msg.action.ordinal());
        buf.writeByte(msg.targetType.ordinal());
        buf.writeBoolean(msg.stance != null);
        if (msg.stance != null) {
            buf.writeByte(msg.stance.ordinal());
        }
        switch (msg.targetType) {
            case PLAYER -> buf.writeUUID(msg.playerKey);
            case TEAM -> buf.writeUtf(msg.stringKey);
            case CATEGORY -> buf.writeUtf(msg.stringKey);
        }
    }

    public static SetFofStancePacket decode(FriendlyByteBuf buf) {
        int actionOrdinal = buf.readUnsignedByte();
        if (actionOrdinal >= Action.values().length) throw new IllegalArgumentException("Invalid FoF action");
        FofTargetType[] types = FofTargetType.values();
        int typeOrdinal = buf.readByte();
        if (typeOrdinal < 0 || typeOrdinal >= types.length) throw new IllegalArgumentException("Invalid FoF target type");
        boolean hasStance = buf.readBoolean();
        FofStance stance = hasStance ? FofStance.fromOrdinal(buf.readByte()) : null;
        if (hasStance && stance == null) throw new IllegalArgumentException("Invalid FoF stance");
        SetFofStancePacket packet = switch (types[typeOrdinal]) {
            case PLAYER -> new SetFofStancePacket(FofTargetType.PLAYER, buf.readUUID(), null, stance);
            case TEAM -> new SetFofStancePacket(FofTargetType.TEAM, null, buf.readUtf(), stance);
            case CATEGORY -> new SetFofStancePacket(FofTargetType.CATEGORY, null, buf.readUtf(), stance);
        };
        packet.action = Action.values()[actionOrdinal];
        return packet;
    }

    public static void handle(SetFofStancePacket msg, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null || player.getServer() == null) return;
            var server = player.getServer();
            if (msg.action == Action.SYNC) {
                NetworkHandler.sendTo(player, FofSyncPacket.createFor(server, player.getUUID()));
                return;
            }
            if (msg.targetType == FofTargetType.PLAYER && msg.playerKey != null) {
                var relations = FofRelations.get(server);
                var other = server.getPlayerList().getPlayer(msg.playerKey);
                String ownTeam = FofSyncPacket.teamName(server, player.getUUID());
                String otherTeam = FofSyncPacket.teamName(server, msg.playerKey);
                long tick = server.overworld().getGameTime();
                FofRelations.ChangeResult result;
                if (msg.action == Action.CANCEL) result = relations.cancelPlayerChange(player.getUUID(), msg.playerKey)
                    ? FofRelations.ChangeResult.APPLIED : FofRelations.ChangeResult.INVALID;
                else if (FofSyncPacket.playerName(server, msg.playerKey) == null
                    || (!com.stevesarmy.StevesArmyConfig.getAllowPlayerDiplomacy() && !player.hasPermissions(2)))
                    result = FofRelations.ChangeResult.INVALID;
                else if (msg.action == Action.ACCEPT) {
                    boolean matches = relations.pendingFor(player.getUUID()).stream().anyMatch(change ->
                        change.requester().equals(msg.playerKey) && change.other().equals(player.getUUID())
                            && !change.acceptedByOther() && change.desired() == msg.stance);
                    result = matches ? relations.acceptPlayerChange(player.getUUID(), msg.playerKey, tick)
                        : FofRelations.ChangeResult.INVALID;
                }
                else result = relations.requestPlayerChange(player.getUUID(), msg.playerKey, msg.stance, ownTeam, otherTeam, tick);
                String message = switch (result) {
                    case APPLIED -> "FoF relationship updated.";
                    case PENDING -> "FoF peace request sent; the other player must accept.";
                    case LOCKED -> msg.action == Action.ACCEPT
                        ? "FoF request accepted; waiting for 30 seconds without combat."
                        : "FoF request saved; agreement and 30 seconds without combat are required.";
                    case INVALID -> "FoF action rejected; refresh the screen or check server diplomacy permissions.";
                };
                player.sendSystemMessage(net.minecraft.network.chat.Component.literal(message));
                if (other != null && result != FofRelations.ChangeResult.INVALID) {
                    String notice = msg.action == Action.ACCEPT && result == FofRelations.ChangeResult.LOCKED
                        ? " accepted your FoF request; it will take effect after combat ends."
                        : result == FofRelations.ChangeResult.PENDING || result == FofRelations.ChangeResult.LOCKED
                            ? " requests an FoF change. Open the FoF tab to review it."
                        : msg.stance == FofStance.HOSTILE && msg.action == Action.SET
                            ? " declared mutual hostility with you and your soldiers."
                            : " changed or responded to your FoF relationship.";
                    other.sendSystemMessage(net.minecraft.network.chat.Component.literal(player.getName().getString() + notice));
                }
                syncAll(server);
            } else if (msg.targetType == FofTargetType.TEAM && msg.action == Action.SET) {
                String ownTeam = FofSyncPacket.teamName(server, player.getUUID());
                if (player.hasPermissions(2) && ownTeam != null && msg.stringKey != null
                    && server.getScoreboard().getPlayerTeam(msg.stringKey) != null
                    && FofRelations.get(server).setTeamRelation(ownTeam, msg.stringKey, msg.stance)) syncAll(server);
            } else if (msg.targetType == FofTargetType.CATEGORY && msg.action == Action.SET) {
                if (FofSettings.applyStance(server, player.getUUID(), msg.targetType, null, msg.stringKey, msg.stance))
                    NetworkHandler.sendTo(player, FofSyncPacket.createFor(server, player.getUUID()));
            }
        });
        context.setPacketHandled(true);
    }

    public static void syncAll(net.minecraft.server.MinecraftServer server) {
        for (ServerPlayer recipient : server.getPlayerList().getPlayers())
            NetworkHandler.sendTo(recipient, FofSyncPacket.createFor(server, recipient.getUUID()));
    }
}
