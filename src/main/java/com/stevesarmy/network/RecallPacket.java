package com.stevesarmy.network;

import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.combat.RecallHelper;
import com.stevesarmy.entity.SoldierEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public class RecallPacket {
    private final int soldierId;

    public RecallPacket(int soldierId) {
        this.soldierId = soldierId;
    }

    public static void encode(RecallPacket msg, FriendlyByteBuf buf) {
        buf.writeInt(msg.soldierId);
    }

    public static RecallPacket decode(FriendlyByteBuf buf) {
        return new RecallPacket(buf.readInt());
    }

    public static void handle(RecallPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;
            if (!(player.level() instanceof ServerLevel serverLevel)) return;

            Entity entity = serverLevel.getEntity(msg.soldierId);
            if (!(entity instanceof SoldierEntity soldier) || !soldier.isOwnedBy(player)) return;
            if (!soldier.isAlive()) return;
            if (soldier.isRecalling()) return;

            soldier.startRecall();
        });
        ctx.get().setPacketHandled(true);
    }
}