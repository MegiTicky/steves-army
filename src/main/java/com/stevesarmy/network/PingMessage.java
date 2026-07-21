package com.stevesarmy.network;

import com.stevesarmy.ping.PingType;
import com.stevesarmy.squad.FireTeam;
import com.stevesarmy.squad.FireTeamAssignment;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkEvent;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public class PingMessage {
    private final PingType type;
    private final double x;
    private final double y;
    private final double z;
    private final int dimension;
    private final FireTeam scope;

    public PingMessage(PingType type, Vec3 position, int dimension) {
        this(type, position, dimension, FireTeam.ALL);
    }

    public PingMessage(PingType type, Vec3 position, int dimension, FireTeam scope) {
        this.type = type;
        this.x = position.x;
        this.y = position.y;
        this.z = position.z;
        this.dimension = dimension;
        this.scope = scope;
    }

    public PingMessage(FriendlyByteBuf buf) {
        this.type = PingType.values()[buf.readInt()];
        this.x = buf.readDouble();
        this.y = buf.readDouble();
        this.z = buf.readDouble();
        this.dimension = buf.readInt();
        this.scope = buf.readEnum(FireTeam.class);
    }

    public static void encode(PingMessage msg, FriendlyByteBuf buf) {
        buf.writeInt(msg.type.ordinal());
        buf.writeDouble(msg.x);
        buf.writeDouble(msg.y);
        buf.writeDouble(msg.z);
        buf.writeInt(msg.dimension);
        buf.writeEnum(msg.scope);
    }

    public PingType getType() { return type; }
    public Vec3 getPosition() { return new Vec3(x, y, z); }
    public int getDimension() { return dimension; }
    public FireTeam getScope() { return scope; }

    public static void handle(PingMessage msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer sender = ctx.get().getSender();
            if (sender == null) return;

            ServerLevel level = sender.serverLevel();
            Vec3 position = msg.getPosition();
            PingType type = msg.getType();
            int dimension = msg.getDimension();
            FireTeam scope = msg.getScope();

            List<com.stevesarmy.entity.SoldierEntity> owned = level.getEntitiesOfClass(
                com.stevesarmy.entity.SoldierEntity.class,
                sender.getBoundingBox().inflate(100),
                s -> s.isOwnedBy(sender)
            );

            // Filter by fire team scope
            if (scope != FireTeam.ALL) {
                FireTeamAssignment fta = FireTeamAssignment.get(level, sender.getUUID());
                List<UUID> teamIds = fta.getSoldiersInTeam(scope);
                owned = owned.stream()
                    .filter(s -> teamIds.contains(s.getUUID()))
                    .collect(Collectors.toList());
            }

            if (type == PingType.SEND) {
                List<com.stevesarmy.entity.SoldierEntity> available = owned.stream()
                    .filter(s -> !s.isDispatchedBySend())
                    .collect(Collectors.toList());

                com.stevesarmy.entity.SoldierEntity target;
                if (available.isEmpty()) {
                    target = owned.stream()
                        .min(java.util.Comparator.comparingDouble(s -> s.distanceToSqr(position)))
                        .orElse(null);
                    if (target != null) {
                        com.stevesarmy.StevesArmyMod.LOGGER.info("SEND: all soldiers dispatched, re-sending nearest");
                    }
                } else {
                    target = available.stream()
                        .min(java.util.Comparator.comparingDouble(s -> s.distanceToSqr(position)))
                        .orElse(null);
                }

                if (target != null) {
                    target.receivePing(type, position);
                    PingBroadcastMessage broadcast = new PingBroadcastMessage(
                        type, position, dimension,
                        sender.getUUID(), sender.getGameProfile().getName(), 0xFF55AAAA,
                        scope.ordinal()
                    );
                    for (ServerPlayer recipient : level.getServer().getPlayerList().getPlayers()) {
                        NetworkHandler.sendTo(recipient, broadcast);
                    }
                }
                return;
            }

            if (type != PingType.FOLLOW && type != PingType.HOLD) {
                int teamColor = 0xFFFFFFFF;
                if (sender.getTeam() != null) {
                    Integer color = sender.getTeam().getColor().getColor();
                    if (color != null) {
                        teamColor = (255 << 24) | color;
                    }
                }

                List<ServerPlayer> recipients;
                if (sender.getTeam() != null) {
                    recipients = level.getServer().getPlayerList().getPlayers().stream()
                        .filter(p -> p.getTeam() == sender.getTeam())
                        .collect(Collectors.toList());
                } else {
                    recipients = level.getServer().getPlayerList().getPlayers();
                }

                for (ServerPlayer recipient : recipients) {
                    PingBroadcastMessage broadcast = new PingBroadcastMessage(
                        type,
                        position,
                        dimension,
                        sender.getUUID(),
                        sender.getGameProfile().getName(),
                        teamColor,
                        scope.ordinal()
                    );
                    NetworkHandler.sendTo(recipient, broadcast);
                }
            }

            for (com.stevesarmy.entity.SoldierEntity soldier : owned) {
                soldier.receivePing(type, position);
            }
        });
        ctx.get().setPacketHandled(true);
    }
}