package com.stevesarmy.network;

import com.stevesarmy.StevesArmyMod;
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
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Crew assign stick: assign the client-selected crew to free seats on the ship under
 * the clicked block. The server re-validates the held item and every crew id, resolves
 * the ship from the anchor (crosshair ship first, else the ship near the player), and
 * seats crew through the shared infantry MOUNT machinery ({@link CrewAssignment}) —
 * the soldier flow: ship + anchor position, never a specific seat entity.
 */
public class CommandStickAssignCrewPacket {
    private final double x;
    private final double y;
    private final double z;
    private final List<Integer> crewIds;

    public CommandStickAssignCrewPacket(Vec3 anchor, List<Integer> crewIds) {
        this.x = anchor.x;
        this.y = anchor.y;
        this.z = anchor.z;
        this.crewIds = List.copyOf(crewIds);
    }

    public CommandStickAssignCrewPacket(FriendlyByteBuf buf) {
        this.x = buf.readDouble();
        this.y = buf.readDouble();
        this.z = buf.readDouble();
        int count = buf.readVarInt();
        List<Integer> ids = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            ids.add(buf.readVarInt());
        }
        this.crewIds = List.copyOf(ids);
    }

    public static void encode(CommandStickAssignCrewPacket msg, FriendlyByteBuf buf) {
        buf.writeDouble(msg.x);
        buf.writeDouble(msg.y);
        buf.writeDouble(msg.z);
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
                StevesArmyMod.LOGGER.info("[CrewStick] packet dropped: main hand is {} (assign stick required)",
                    mainHand.getItem());
                return;
            }
            Vec3 anchor = new Vec3(msg.x, msg.y, msg.z);
            Object ship = VS2Compat.getShipObjectAtWorldPos(level, msg.x, msg.y, msg.z);
            if (ship == null) {
                ship = VS2Compat.resolveMountShipNearPlayer(level, sender);
            }
            if (ship == null) {
                StevesArmyMod.LOGGER.info("[CrewStick] packet dropped: no ship at anchor {} or near player",
                    anchor);
                sender.displayClientMessage(
                    Component.translatable("transport.steves_army.feedback.no_vehicle"), true);
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
                StevesArmyMod.LOGGER.info(
                    "[CrewStick] packet dropped: none of the {} ids resolved to owned vehicle crew",
                    msg.crewIds.size());
                return;
            }
            StevesArmyMod.LOGGER.info("[CrewStick] packet accepted: anchor={} crew={}/{} shipId={}",
                anchor, crew.size(), msg.crewIds.size(), VS2Compat.getShipIdOf(ship));
            int seated = CrewAssignment.mountCrewOnShip(level, ship, anchor, crew);
            sender.displayClientMessage(
                Component.translatable("transport.steves_army.feedback.crew_assigned", seated, crew.size()), true);
        });
        ctx.get().setPacketHandled(true);
    }
}
