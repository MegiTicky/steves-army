package com.stevesarmy.network;

import com.stevesarmy.squad.FofCategory;
import com.stevesarmy.squad.FofSettings;
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
        FofTargetType[] types = FofTargetType.values();
        int typeOrdinal = buf.readByte();
        // Never return null from decode — clamp out-of-range ordinals like the rest of the mod.
        if (typeOrdinal < 0 || typeOrdinal >= types.length) typeOrdinal = 0;
        FofStance stance = buf.readBoolean() ? FofStance.fromOrdinal(buf.readByte()) : null;
        return switch (types[typeOrdinal]) {
            case PLAYER -> new SetFofStancePacket(FofTargetType.PLAYER, buf.readUUID(), null, stance);
            case TEAM -> new SetFofStancePacket(FofTargetType.TEAM, null, buf.readUtf(), stance);
            case CATEGORY -> new SetFofStancePacket(FofTargetType.CATEGORY, null, buf.readUtf(), stance);
        };
    }

    public static void handle(SetFofStancePacket msg, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null || player.getServer() == null) return;
            boolean applied = FofSettings.applyStance(player.getServer(), player.getUUID(),
                msg.targetType, msg.playerKey, msg.stringKey, msg.stance);
            if (applied) {
                NetworkHandler.sendTo(player, FofSyncPacket.createFor(player.getServer(), player.getUUID()));
            }
        });
        context.setPacketHandled(true);
    }
}
