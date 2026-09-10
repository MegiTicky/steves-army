package com.stevesarmy.network;

import com.stevesarmy.compat.VS2Compat;
import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.entity.SoldierRole;
import com.stevesarmy.item.CrewAssignStickItem;
import com.stevesarmy.transport.CrewAssignment;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Crew assign stick: assign the client-selected crew to the shift-clicked seat, then
 * to the seat ship's other free seats. The server re-validates the held item, seat,
 * and every crew id.
 */
public class CommandStickAssignCrewPacket {
    private final int seatEntityId;
    private final List<Integer> crewIds;

    public CommandStickAssignCrewPacket(int seatEntityId, List<Integer> crewIds) {
        this.seatEntityId = seatEntityId;
        this.crewIds = List.copyOf(crewIds);
    }

    public CommandStickAssignCrewPacket(FriendlyByteBuf buf) {
        this.seatEntityId = buf.readVarInt();
        int count = buf.readVarInt();
        List<Integer> ids = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            ids.add(buf.readVarInt());
        }
        this.crewIds = List.copyOf(ids);
    }

    public static void encode(CommandStickAssignCrewPacket msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.seatEntityId);
        buf.writeVarInt(msg.crewIds.size());
        for (int id : msg.crewIds) {
            buf.writeVarInt(id);
        }
    }

    public static void handle(CommandStickAssignCrewPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer sender = ctx.get().getSender();
            if (sender == null || !(sender.level() instanceof ServerLevel level)) {
                return;
            }
            ItemStack mainHand = sender.getMainHandItem();
            if (!(mainHand.getItem() instanceof CrewAssignStickItem)) {
                return;
            }
            Entity seat = level.getEntity(msg.seatEntityId);
            if (seat == null || seat.isRemoved() || !VS2Compat.isCreateSeatEntity(seat)) {
                return;
            }
            List<SoldierEntity> crew = new ArrayList<>();
            for (int id : msg.crewIds) {
                Entity entity = level.getEntity(id);
                if (entity instanceof SoldierEntity soldier
                    && soldier.getRole() == SoldierRole.VEHICLE_CREW
                    && soldier.isOwnedBy(sender)
                    && !crew.contains(soldier)) {
                    crew.add(soldier);
                }
            }
            if (crew.isEmpty()) {
                return;
            }
            int seated = CrewAssignment.assignToClickedSeat(level, seat, crew);
            sender.displayClientMessage(
                Component.translatable("transport.steves_army.feedback.crew_assigned", seated, crew.size()), true);
        });
        ctx.get().setPacketHandled(true);
    }
}
