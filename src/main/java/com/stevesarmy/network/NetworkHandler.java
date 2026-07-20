package com.stevesarmy.network;

import com.stevesarmy.StevesArmyMod;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

public class NetworkHandler {
    private static final String PROTOCOL_VERSION = "1";
    
    public static final SimpleChannel INSTANCE = NetworkRegistry.newSimpleChannel(
        new ResourceLocation(StevesArmyMod.MODID, "main"),
        () -> PROTOCOL_VERSION,
        PROTOCOL_VERSION::equals,
        PROTOCOL_VERSION::equals
    );
    
    public static void register() {
        int id = 0;
        INSTANCE.registerMessage(id++, ToggleSquadModeMessage.class, 
            ToggleSquadModeMessage::encode, 
            ToggleSquadModeMessage::decode, 
            ToggleSquadModeMessage::handle);
        INSTANCE.registerMessage(id++, DebugMessage.class,
            DebugMessage::encode,
            DebugMessage::decode,
            DebugMessage::handle);
        INSTANCE.registerMessage(id++, OpenSoldierInventoryMessage.class,
            OpenSoldierInventoryMessage::encode,
            OpenSoldierInventoryMessage::decode,
            OpenSoldierInventoryMessage::handle);
        INSTANCE.registerMessage(id++, PingMessage.class,
            PingMessage::encode,
            PingMessage::new,
            PingMessage::handle);
        INSTANCE.registerMessage(id++, PingBroadcastMessage.class,
            PingBroadcastMessage::encode,
            PingBroadcastMessage::new,
            PingBroadcastMessage::handle);
        INSTANCE.registerMessage(id++, PotentialTargetsDebugMessage.class,
            PotentialTargetsDebugMessage::encode,
            PotentialTargetsDebugMessage::new,
            PotentialTargetsDebugMessage::handle);
        INSTANCE.registerMessage(id++, FormationMessage.class,
            FormationMessage::encode,
            FormationMessage::new,
            FormationMessage::handle);
        INSTANCE.registerMessage(id++, SyncSoldierInventoryPacket.class,
            SyncSoldierInventoryPacket::encode,
            SyncSoldierInventoryPacket::decode,
            SyncSoldierInventoryPacket::handle);
        INSTANCE.registerMessage(id++, CQBToggleMessage.class,
            CQBToggleMessage::encode,
            CQBToggleMessage::decode,
            CQBToggleMessage::handle);
        INSTANCE.registerMessage(id++, SquadStatusSyncPacket.class,
            SquadStatusSyncPacket::encode,
            SquadStatusSyncPacket::decode,
            SquadStatusSyncPacket::handle);
        INSTANCE.registerMessage(id++, SetSoldierConfigPacket.class,
            SetSoldierConfigPacket::encode,
            SetSoldierConfigPacket::decode,
            SetSoldierConfigPacket::handle);
    }

    public static void sendTo(ServerPlayer player, Object message) {
        INSTANCE.send(PacketDistributor.PLAYER.with(() -> player), message);
    }
}