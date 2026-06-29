package com.stevesarmy.network;

import com.stevesarmy.ping.PingType;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public class PingMessage {
    private final PingType type;
    private final double x;
    private final double y;
    private final double z;
    private final int dimension;
    
    public PingMessage(PingType type, Vec3 position, int dimension) {
        this.type = type;
        this.x = position.x;
        this.y = position.y;
        this.z = position.z;
        this.dimension = dimension;
    }
    
    public PingMessage(FriendlyByteBuf buf) {
        this.type = PingType.values()[buf.readInt()];
        this.x = buf.readDouble();
        this.y = buf.readDouble();
        this.z = buf.readDouble();
        this.dimension = buf.readInt();
    }
    
    public static void encode(PingMessage msg, FriendlyByteBuf buf) {
        buf.writeInt(msg.type.ordinal());
        buf.writeDouble(msg.x);
        buf.writeDouble(msg.y);
        buf.writeDouble(msg.z);
        buf.writeInt(msg.dimension);
    }
    
    public PingType getType() { return type; }
    public Vec3 getPosition() { return new Vec3(x, y, z); }
    public int getDimension() { return dimension; }
    
    public static void handle(PingMessage msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer sender = ctx.get().getSender();
            if (sender == null) return;
            
            ServerLevel level = sender.serverLevel();
            Vec3 position = msg.getPosition();
            PingType type = msg.getType();
            int dimension = msg.getDimension();
            
            com.stevesarmy.StevesArmyMod.LOGGER.info("PingMessage received: type={}, pos={}, dimension={}", type, position, dimension);
            
            if (type != PingType.FOLLOW && type != PingType.HOLD) {
                int teamColor = 0xFFFFFFFF;
                if (sender.getTeam() != null) {
                    Integer color = sender.getTeam().getColor().getColor();
                    if (color != null) {
                        teamColor = (255 << 24) | color;
                    }
                }
                
                java.util.List<ServerPlayer> recipients;
                if (sender.getTeam() != null) {
                    recipients = level.getServer().getPlayerList().getPlayers().stream()
                        .filter(p -> p.getTeam() == sender.getTeam())
                        .collect(java.util.stream.Collectors.toList());
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
                        teamColor
                    );
                    NetworkHandler.sendTo(recipient, broadcast);
                }
            }
            
            java.util.List<com.stevesarmy.entity.SoldierEntity> soldiers = level.getEntitiesOfClass(
                com.stevesarmy.entity.SoldierEntity.class,
                sender.getBoundingBox().inflate(100),
                s -> s.isOwnedBy(sender)
            );
            
            com.stevesarmy.StevesArmyMod.LOGGER.info("Found {} soldiers owned by player within 100 blocks", soldiers.size());
            
            for (com.stevesarmy.entity.SoldierEntity soldier : soldiers) {
                soldier.receivePing(type, position);
                com.stevesarmy.StevesArmyMod.LOGGER.info("Sent ping to soldier at {}", soldier.blockPosition());
            }
        });
        ctx.get().setPacketHandled(true);
    }
}