package com.stevesarmy.network;

import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.network.SpacingDebugPacket;
import com.stevesarmy.network.SpacingDebugPacket.SpacingDebugEntry;
import com.stevesarmy.ping.PingType;
import com.stevesarmy.squad.FireTeam;
import com.stevesarmy.squad.FireTeamAssignment;
import com.stevesarmy.squad.SquadLaneAssignment;
import com.stevesarmy.squad.SquadActivityManager;
import com.stevesarmy.squad.SquadActivityType;
import com.stevesarmy.squad.SquadManager;
import com.stevesarmy.util.SpacingHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
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

            List<SoldierEntity> owned = resolveSquadSoldiers(level, sender, scope);

            // GO_TO, SEND, and ATTACK should also clear stale ATTACK state
            if (type == PingType.GO_TO || type == PingType.SEND || type == PingType.ATTACK) {
                for (SoldierEntity soldier : owned) {
                    soldier.clearAttackTarget();
                }
            }

            if (type == PingType.SEND) {
                List<SoldierEntity> available = owned.stream()
                    .filter(s -> !s.isDispatchedBySend())
                    .collect(Collectors.toList());

                SoldierEntity target;
                if (available.isEmpty()) {
                    target = owned.stream()
                        .min(java.util.Comparator.comparingDouble(s -> s.distanceToSqr(position)))
                        .orElse(null);
                    if (target != null) {
                        StevesArmyMod.LOGGER.info("SEND: all soldiers dispatched, re-sending nearest");
                    }
                } else {
                    target = available.stream()
                        .min(java.util.Comparator.comparingDouble(s -> s.distanceToSqr(position)))
                        .orElse(null);
                }

                if (target != null) {
                    target.receivePing(type, position);
                    SquadActivityManager.applyCommand(
                        sender, scope, type, BlockPos.containing(position), dimension, List.of(target));
                    PingBroadcastMessage broadcast = new PingBroadcastMessage(
                        type, position, dimension,
                        sender.getUUID(), sender.getGameProfile().getName(), 0xFF55AAAA,
                        scope.ordinal()
                    );
                    for (ServerPlayer recipient : level.getServer().getPlayerList().getPlayers()) {
                        if (!recipient.getUUID().equals(sender.getUUID())) {
                            NetworkHandler.sendTo(recipient, broadcast);
                        }
                    }
                } else {
                    StevesArmyMod.LOGGER.warn("SEND: no owned soldiers found in squad for player {}", sender.getName().getString());
                }
                return;
            }

            if (type != PingType.FOLLOW && type != PingType.HOLD) {
                boolean hasPersistentActivity = SquadActivityType.fromPingType(type) != null;
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
                        .filter(p -> !hasPersistentActivity || !p.getUUID().equals(sender.getUUID()))
                        .collect(Collectors.toList());
                } else {
                    recipients = level.getServer().getPlayerList().getPlayers().stream()
                        .filter(p -> !hasPersistentActivity || !p.getUUID().equals(sender.getUUID()))
                        .collect(Collectors.toList());
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

            for (SoldierEntity soldier : owned) {
                soldier.receivePing(type, position);
            }

            SquadActivityManager.applyCommand(
                sender, scope, type, BlockPos.containing(position), dimension, owned);

            // Eagerly create lane assignment and send debug packet for GO_TO and ATTACK
            if ((type == PingType.GO_TO || type == PingType.ATTACK) && !owned.isEmpty()) {
                BlockPos targetPos = BlockPos.containing(position);
                SquadLaneAssignment assignment = SpacingHelper.createAssignment(targetPos, owned);
                if (assignment != null && com.stevesarmy.client.SpacingDebugRenderer.isEnabled()) {
                    List<SpacingDebugEntry> debugEntries = new ArrayList<>();
                    for (SoldierEntity s : owned) {
                        if (assignment.getSlot(s.getUUID()) != null) {
                            debugEntries.add(SpacingDebugEntry.fromAssignment(s, assignment));
                        }
                    }
                    NetworkHandler.sendTo(sender, new SpacingDebugPacket(true, debugEntries));
                }
            }
        });
        ctx.get().setPacketHandled(true);
    }

    private static List<SoldierEntity> resolveSquadSoldiers(ServerLevel level, ServerPlayer sender, FireTeam scope) {
        SquadManager mgr = SquadManager.get(level);
        java.util.Optional<com.stevesarmy.squad.SquadData> squadOpt = mgr.getSquadByLeader(sender.getUUID());
        if (squadOpt.isEmpty()) {
            // No squad — fall back to nearby owned soldiers (within 100 blocks)
            return level.getEntitiesOfClass(
                SoldierEntity.class,
                sender.getBoundingBox().inflate(100),
                s -> s.isOwnedBy(sender)
            );
        }

        com.stevesarmy.squad.SquadData squad = squadOpt.get();
        List<LivingEntity> members = mgr.getSquadMembers(level, squad.getSquadId(), null);
        List<SoldierEntity> result = new ArrayList<>();
        for (LivingEntity member : members) {
            if (member instanceof SoldierEntity s && s.isAlive() && s.isOwnedBy(sender)) {
                result.add(s);
            }
        }
        // Add any owned soldiers not in squad that are within 100 blocks as a fallback (UUID dedup)
        java.util.Set<UUID> resultUuids = result.stream().map(s -> s.getUUID()).collect(java.util.stream.Collectors.toSet());
        List<SoldierEntity> nearbyFallback = level.getEntitiesOfClass(
            SoldierEntity.class,
            sender.getBoundingBox().inflate(100),
            s -> s.isOwnedBy(sender) && !resultUuids.contains(s.getUUID())
        );
        result.addAll(nearbyFallback);

        // Filter by fire team scope
        if (scope != FireTeam.ALL) {
            FireTeamAssignment fta = FireTeamAssignment.get(level, sender.getUUID());
            List<UUID> teamIds = fta.getSoldiersInTeam(scope);
            result = result.stream()
                .filter(s -> teamIds.contains(s.getUUID()))
                .collect(Collectors.toList());
        }

        if (result.isEmpty()) {
            StevesArmyMod.LOGGER.warn("PingMessage: no owned soldiers found for player {} (squad={})",
                sender.getName().getString(), squadOpt.isPresent());
        }

        return result;
    }
}
