package com.stevesarmy.network;

import com.stevesarmy.entity.SoldierEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public class SyncSoldierInventoryPacket {
    private final int soldierId;
    private final CompoundTag inventoryTag;

    public SyncSoldierInventoryPacket(int soldierId, CompoundTag inventoryTag) {
        this.soldierId = soldierId;
        this.inventoryTag = inventoryTag;
    }

    public static void encode(SyncSoldierInventoryPacket msg, FriendlyByteBuf buf) {
        buf.writeInt(msg.soldierId);
        buf.writeNbt(msg.inventoryTag);
    }

    public static SyncSoldierInventoryPacket decode(FriendlyByteBuf buf) {
        int soldierId = buf.readInt();
        CompoundTag tag = buf.readNbt();
        return new SyncSoldierInventoryPacket(soldierId, tag != null ? tag : new CompoundTag());
    }

    public static void handle(SyncSoldierInventoryPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> handleClient(msg));
        });
        ctx.get().setPacketHandled(true);
    }

    private static void handleClient(SyncSoldierInventoryPacket msg) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        
        Entity entity = mc.level.getEntity(msg.soldierId);
        if (entity instanceof SoldierEntity soldier) {
            if (msg.inventoryTag.contains("Inventory")) {
                soldier.getSoldierInventory().load(msg.inventoryTag.getCompound("Inventory"));
            } else {
                soldier.getSoldierInventory().load(msg.inventoryTag);
            }
            soldier.getSoldierInventory().syncArmorToEntity(soldier);
        }
    }
}
