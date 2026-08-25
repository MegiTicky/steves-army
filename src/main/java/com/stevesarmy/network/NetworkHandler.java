package com.stevesarmy.network;

import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.compat.ysm.YsmCompat;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;

public class NetworkHandler {
    private static final String PROTOCOL_VERSION = "6";
    
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
        INSTANCE.registerMessage(id++, SquadActivitySyncPacket.class,
            SquadActivitySyncPacket::encode,
            SquadActivitySyncPacket::new,
            SquadActivitySyncPacket::handle);
        INSTANCE.registerMessage(id++, SetSoldierConfigPacket.class,
            SetSoldierConfigPacket::encode,
            SetSoldierConfigPacket::decode,
            SetSoldierConfigPacket::handle);
        INSTANCE.registerMessage(id++, SetFireTeamPacket.class,
            SetFireTeamPacket::encode,
            SetFireTeamPacket::decode,
            SetFireTeamPacket::handle);
        INSTANCE.registerMessage(id++, SpacingDebugPacket.class,
            SpacingDebugPacket::encode,
            SpacingDebugPacket::new,
            SpacingDebugPacket::handle);
        INSTANCE.registerMessage(id++, MachineGunnerEvaluationPacket.class,
            MachineGunnerEvaluationPacket::encode,
            MachineGunnerEvaluationPacket::new,
            MachineGunnerEvaluationPacket::handle);
        INSTANCE.registerMessage(id++, FormationMessage.class,
            FormationMessage::encode,
            FormationMessage::new,
            FormationMessage::handle);
        INSTANCE.registerMessage(id++, RecallPacket.class,
            RecallPacket::encode,
            RecallPacket::decode,
            RecallPacket::handle);
        INSTANCE.registerMessage(id++, FireTeamScopeSyncPacket.class,
            FireTeamScopeSyncPacket::encode,
            FireTeamScopeSyncPacket::decode,
            FireTeamScopeSyncPacket::handle);
        INSTANCE.registerMessage(id++, SetSelectedFireTeamPacket.class,
            SetSelectedFireTeamPacket::encode,
            SetSelectedFireTeamPacket::decode,
            SetSelectedFireTeamPacket::handle);
        INSTANCE.registerMessage(id++, DismissSoldierPacket.class,
            DismissSoldierPacket::encode,
            DismissSoldierPacket::decode,
            DismissSoldierPacket::handle);
        INSTANCE.registerMessage(id++, EnemyContactSyncPacket.class,
            EnemyContactSyncPacket::encode,
            EnemyContactSyncPacket::new,
            EnemyContactSyncPacket::handle);
        INSTANCE.registerMessage(id++, SetSoldierRolePacket.class,
            SetSoldierRolePacket::encode,
            SetSoldierRolePacket::decode,
            SetSoldierRolePacket::handle);
        INSTANCE.registerMessage(id++, BulkGarrisonPacket.class,
            BulkGarrisonPacket::encode,
            BulkGarrisonPacket::decode,
            BulkGarrisonPacket::handle);
        if (YsmCompat.isLoaded()) {
            registerYsmPacket(id++);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void registerYsmPacket(int id) {
        try {
            Class packetClass = Class.forName("com.stevesarmy.compat.ysm.C2SRequestSoldierModelPacket");
            java.lang.reflect.Method encode = packetClass.getMethod("encode", packetClass, FriendlyByteBuf.class);
            java.lang.reflect.Method decode = packetClass.getMethod("decode", FriendlyByteBuf.class);
            java.lang.reflect.Method handle = packetClass.getMethod("handle", packetClass, Supplier.class);
            INSTANCE.registerMessage(id, packetClass,
                (BiConsumer) (message, buffer) -> invokeStatic(encode, message, buffer),
                (Function) buffer -> invokeStatic(decode, buffer),
                (BiConsumer) (message, context) -> invokeStatic(handle, message, context));
        } catch (Throwable throwable) {
            StevesArmyMod.LOGGER.warn("[YSM] Failed to register optional packet: {}", throwable.toString());
        }
    }

    private static Object invokeStatic(java.lang.reflect.Method method, Object... args) {
        try {
            return method.invoke(null, args);
        } catch (ReflectiveOperationException exception) {
            throw new RuntimeException(exception);
        }
    }

    public static void sendTo(ServerPlayer player, Object message) {
        INSTANCE.send(PacketDistributor.PLAYER.with(() -> player), message);
    }

    public static void sendToTracking(Entity entity, Object message) {
        INSTANCE.send(PacketDistributor.TRACKING_ENTITY.with(() -> entity), message);
    }
}
