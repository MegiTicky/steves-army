package com.stevesarmy.network;

import com.stevesarmy.entity.GarrisonEntity;
import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.item.CommandStickItem;
import com.stevesarmy.squad.SquadMode;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

public class CommandStickMovePacket {

    private final BlockPos targetPos;
    private final List<Integer> entityIds;

    public CommandStickMovePacket(BlockPos targetPos, List<Integer> entityIds) {
        this.targetPos = targetPos;
        this.entityIds = entityIds;
    }

    public static void encode(CommandStickMovePacket message, FriendlyByteBuf buffer) {
        buffer.writeBlockPos(message.targetPos);
        buffer.writeInt(message.entityIds.size());
        for (int id : message.entityIds) {
            buffer.writeInt(id);
        }
    }

    public static CommandStickMovePacket decode(FriendlyByteBuf buffer) {
        BlockPos pos = buffer.readBlockPos();
        int count = buffer.readInt();
        List<Integer> ids = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            ids.add(buffer.readInt());
        }
        return new CommandStickMovePacket(pos, ids);
    }

    public static void handle(CommandStickMovePacket message, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null) return;

            if (!(player.getMainHandItem().getItem() instanceof CommandStickItem stick)) return;

            int moved = 0;
            for (int entityId : message.entityIds) {
                Entity entity = player.level().getEntity(entityId);
                if (!(entity instanceof SoldierEntity soldier)) continue;
                if (!stick.isTargetable(soldier, player)) continue;

                moveSoldier(soldier, message.targetPos);
                moved++;
            }

            if (moved > 0) {
                player.displayClientMessage(
                    net.minecraft.network.chat.Component.literal(
                        "Moved " + moved + " soldier" + (moved == 1 ? "" : "s")),
                    true);
            }
        });
        context.setPacketHandled(true);
    }

    private static void moveSoldier(SoldierEntity soldier, BlockPos targetPos) {
        soldier.setSquadMode(SquadMode.HOLD);
        soldier.setHoldPosition(targetPos);

        if (soldier instanceof GarrisonEntity garrison) {
            garrison.setDefendPosition(targetPos);
        }

        soldier.getCoverBehaviorManager().clearCover();

        soldier.getNavigation().moveTo(
            targetPos.getX() + 0.5,
            targetPos.getY(),
            targetPos.getZ() + 0.5,
            1.0);
    }
}
