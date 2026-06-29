package com.stevesarmy.network;

import com.stevesarmy.ping.Ping;
import com.stevesarmy.ping.PingManager;
import com.stevesarmy.ping.PingType;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

public class PingBroadcastMessage {
    private final PingType type;
    private final double x;
    private final double y;
    private final double z;
    private final int dimension;
    private final UUID authorId;
    private final String authorName;
    private final int teamColor;
    
    public PingBroadcastMessage(PingType type, Vec3 position, int dimension, UUID authorId, String authorName, int teamColor) {
        this.type = type;
        this.x = position.x;
        this.y = position.y;
        this.z = position.z;
        this.dimension = dimension;
        this.authorId = authorId;
        this.authorName = authorName;
        this.teamColor = teamColor;
    }
    
    public PingBroadcastMessage(FriendlyByteBuf buf) {
        this.type = PingType.values()[buf.readInt()];
        this.x = buf.readDouble();
        this.y = buf.readDouble();
        this.z = buf.readDouble();
        this.dimension = buf.readInt();
        this.authorId = buf.readUUID();
        this.authorName = buf.readUtf(64);
        this.teamColor = buf.readInt();
    }
    
    public static void encode(PingBroadcastMessage msg, FriendlyByteBuf buf) {
        buf.writeInt(msg.type.ordinal());
        buf.writeDouble(msg.x);
        buf.writeDouble(msg.y);
        buf.writeDouble(msg.z);
        buf.writeInt(msg.dimension);
        buf.writeUUID(msg.authorId);
        buf.writeUtf(msg.authorName, 64);
        buf.writeInt(msg.teamColor);
    }
    
    public static void handle(PingBroadcastMessage msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            Ping ping = new Ping(
                msg.authorId,
                msg.authorName,
                msg.type,
                new Vec3(msg.x, msg.y, msg.z),
                msg.dimension,
                msg.teamColor
            );
            PingManager.addPing(ping);
        });
        ctx.get().setPacketHandled(true);
    }
}