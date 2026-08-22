package com.stevesarmy.network;

import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.entity.SoldierRole;
import com.stevesarmy.entity.SoldierRoleHandler;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** Requests a server-side role conversion for an owned soldier. */
public class SetSoldierRolePacket {
    private final int soldierId;
    private final int roleOrdinal;

    public SetSoldierRolePacket(int soldierId, SoldierRole role) {
        this.soldierId = soldierId;
        this.roleOrdinal = role.ordinal();
    }

    private SetSoldierRolePacket(int soldierId, int roleOrdinal) {
        this.soldierId = soldierId;
        this.roleOrdinal = roleOrdinal;
    }

    public static void encode(SetSoldierRolePacket message, FriendlyByteBuf buffer) {
        buffer.writeInt(message.soldierId);
        buffer.writeInt(message.roleOrdinal);
    }

    public static SetSoldierRolePacket decode(FriendlyByteBuf buffer) {
        return new SetSoldierRolePacket(buffer.readInt(), buffer.readInt());
    }

    public static void handle(SetSoldierRolePacket message, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null) {
                return;
            }

            Entity entity = player.level().getEntity(message.soldierId);
            if (!(entity instanceof SoldierEntity soldier) || !soldier.isOwnedBy(player)) {
                return;
            }
            if (player.distanceToSqr(soldier) > 400.0) {
                return;
            }

            SoldierRole[] roles = SoldierRole.values();
            if (message.roleOrdinal < 0 || message.roleOrdinal >= roles.length) {
                return;
            }
            SoldierRoleHandler.convertSoldier(soldier, roles[message.roleOrdinal]);
        });
        context.setPacketHandled(true);
    }
}
