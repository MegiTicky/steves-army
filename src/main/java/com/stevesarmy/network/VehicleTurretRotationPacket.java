package com.stevesarmy.network;

import com.stevesarmy.compat.sbw.SbwReflection;
import com.stevesarmy.compat.sbw.SbwVehicles;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

/** Synchronizes SBW's otherwise unsynchronized, custom-AI-controlled station rotations. */
public final class VehicleTurretRotationPacket {
    private final int entityId;
    private final UUID entityUuid;
    private final boolean turret;
    private final float yaw;
    private final float pitch;

    private VehicleTurretRotationPacket(int entityId, UUID entityUuid, boolean turret, float yaw, float pitch) {
        this.entityId = entityId;
        this.entityUuid = entityUuid;
        this.turret = turret;
        this.yaw = yaw;
        this.pitch = pitch;
    }

    public static VehicleTurretRotationPacket capture(Entity vehicle, int seat) {
        boolean turret = seat == SbwVehicles.turretControllerIndex(vehicle);
        if (!turret && seat != SbwVehicles.passengerWeaponStationControllerIndex(vehicle)) return null;
        String yawName = turret ? "turretYRot" : "gunYRot";
        String pitchName = turret ? "turretXRot" : "gunXRot";
        Object yaw = SbwReflection.get(vehicle, yawName, turret ? "getTurretYRot" : "getGunYRot");
        Object pitch = SbwReflection.get(vehicle, pitchName, turret ? "getTurretXRot" : "getGunXRot");
        if (!(yaw instanceof Number y) || !(pitch instanceof Number p)) return null;
        return new VehicleTurretRotationPacket(vehicle.getId(), vehicle.getUUID(), turret,
            y.floatValue(), p.floatValue());
    }

    public static void encode(VehicleTurretRotationPacket msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.entityId);
        buf.writeUUID(msg.entityUuid);
        buf.writeBoolean(msg.turret);
        buf.writeFloat(msg.yaw);
        buf.writeFloat(msg.pitch);
    }

    public static VehicleTurretRotationPacket decode(FriendlyByteBuf buf) {
        return new VehicleTurretRotationPacket(buf.readVarInt(), buf.readUUID(),
            buf.readBoolean(), buf.readFloat(), buf.readFloat());
    }

    public static void handle(VehicleTurretRotationPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
            () -> () -> com.stevesarmy.client.ClientVehicleTurretSync.receive(
                msg.entityId, msg.entityUuid, msg.turret, msg.yaw, msg.pitch)));
        ctx.get().setPacketHandled(true);
    }

}
