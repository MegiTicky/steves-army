package com.stevesarmy.network;

import com.stevesarmy.squad.OwnedSoldierRegistry;
import com.stevesarmy.squad.ResupplyConfig;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** Updates the squad-wide resupply config for the sending player's squad. */
public class SetResupplyConfigPacket {
    private final int ammoThreshold;
    private final int healingThreshold;
    private final int resupplyToAmmo;
    private final int resupplyToHeals;

    public SetResupplyConfigPacket(ResupplyConfig config) {
        this(config.ammoThreshold(), config.healingThreshold(), config.resupplyToAmmo(), config.resupplyToHeals());
    }

    public SetResupplyConfigPacket(int ammoThreshold, int healingThreshold, int resupplyToAmmo, int resupplyToHeals) {
        this.ammoThreshold = ammoThreshold;
        this.healingThreshold = healingThreshold;
        this.resupplyToAmmo = resupplyToAmmo;
        this.resupplyToHeals = resupplyToHeals;
    }

    public static void encode(SetResupplyConfigPacket message, FriendlyByteBuf buffer) {
        buffer.writeVarInt(message.ammoThreshold);
        buffer.writeVarInt(message.healingThreshold);
        buffer.writeVarInt(message.resupplyToAmmo);
        buffer.writeVarInt(message.resupplyToHeals);
    }

    public static SetResupplyConfigPacket decode(FriendlyByteBuf buffer) {
        return new SetResupplyConfigPacket(
            buffer.readVarInt(),
            buffer.readVarInt(),
            buffer.readVarInt(),
            buffer.readVarInt());
    }

    public static void handle(SetResupplyConfigPacket message, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null || player.getServer() == null) return;

            ResupplyConfig config = new ResupplyConfig(
                message.ammoThreshold,
                message.healingThreshold,
                message.resupplyToAmmo,
                message.resupplyToHeals);
            OwnedSoldierRegistry registry = OwnedSoldierRegistry.get(player.getServer());
            registry.setResupplyConfig(player.getUUID(), config);
            NetworkHandler.sendTo(player, SquadStatusSyncPacket.createForPlayer(player));
        });
        context.setPacketHandled(true);
    }
}
