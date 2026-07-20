package com.stevesarmy.network;

import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.squad.FireDiscipline;
import com.stevesarmy.squad.SquadMode;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

public class SetSoldierConfigPacket {
    private final UUID soldierId;
    private final ConfigType configType;
    private final int value;

    public enum ConfigType {
        SQUAD_MODE,
        FIRE_DISCIPLINE
    }

    public SetSoldierConfigPacket(UUID soldierId, ConfigType configType, int value) {
        this.soldierId = soldierId;
        this.configType = configType;
        this.value = value;
    }

    public static SetSoldierConfigPacket decode(FriendlyByteBuf buf) {
        UUID soldierId = buf.readUUID();
        ConfigType configType = ConfigType.values()[buf.readVarInt() % ConfigType.values().length];
        int value = buf.readVarInt();
        return new SetSoldierConfigPacket(soldierId, configType, value);
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUUID(soldierId);
        buf.writeVarInt(configType.ordinal());
        buf.writeVarInt(value);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            var sender = ctx.get().getSender();
            if (sender == null) return;
            if (!(sender.level() instanceof ServerLevel)) return;
            ServerLevel serverLevel = (ServerLevel) sender.level();

            Entity entity = serverLevel.getEntity(soldierId);
            if (!(entity instanceof SoldierEntity soldier)) return;
            if (!soldier.isOwnedBy(sender)) return;

            switch (configType) {
                case SQUAD_MODE -> {
                    SquadMode mode = SquadMode.values()[value % SquadMode.values().length];
                    soldier.setSquadMode(mode);
                }
                case FIRE_DISCIPLINE -> {
                    FireDiscipline discipline = FireDiscipline.values()[value % FireDiscipline.values().length];
                    soldier.setFireDiscipline(discipline);
                }
            }
        });
        ctx.get().setPacketHandled(true);
    }
}