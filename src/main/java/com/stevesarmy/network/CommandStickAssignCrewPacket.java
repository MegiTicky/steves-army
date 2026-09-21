package com.stevesarmy.network;

import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.compat.SbwCompat;
import com.stevesarmy.compat.VS2Compat;
import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.entity.SoldierRole;
import com.stevesarmy.item.CreativeCommandStickItem;
import com.stevesarmy.item.CrewAssignStickItem;
import com.stevesarmy.transport.CrewAssignment;
import net.minecraft.core.BlockPos;
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
    private final BlockPos hitBlock;
    /** Crosshair Superb Warfare vehicle entity id, 0 when the aim was not an entity. */
    private final int vehicleEntityId;
    private final List<Integer> crewIds;

    public CommandStickAssignCrewPacket(Vec3 anchor, BlockPos hitBlock, List<Integer> crewIds) {
        this(anchor, hitBlock, 0, crewIds);
    }

    public CommandStickAssignCrewPacket(Vec3 anchor, BlockPos hitBlock, int vehicleEntityId,
                                        List<Integer> crewIds) {
        this.x = anchor.x;
        this.y = anchor.y;
        this.z = anchor.z;
        this.hitBlock = hitBlock;
        this.vehicleEntityId = vehicleEntityId;
        this.crewIds = List.copyOf(crewIds);
    }

    public CommandStickAssignCrewPacket(FriendlyByteBuf buf) {
        this.x = buf.readDouble();
        this.y = buf.readDouble();
        this.z = buf.readDouble();
        this.hitBlock = buf.readBlockPos();
        this.vehicleEntityId = buf.readVarInt();
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
        buf.writeBlockPos(msg.hitBlock);
        buf.writeVarInt(msg.vehicleEntityId);
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
            boolean ownedCrewTool = mainHand.getItem() instanceof CrewAssignStickItem;
            boolean creativeCrewTool = mainHand.getItem() instanceof CreativeCommandStickItem
                && sender.getAbilities().instabuild;
            if (!ownedCrewTool && !creativeCrewTool) {
                StevesArmyMod.LOGGER.info("[CrewStick] packet dropped: main hand is {} (crew assignment tool required)",
                    mainHand.getItem());
                return;
            }
            Vec3 anchor = new Vec3(msg.x, msg.y, msg.z);
            // Crosshair Superb Warfare vehicle first: mount on the entity directly,
            // skipping ship resolution (a vehicle click resolves no ship and would
            // drop as "no ship at anchor" below).
            if (msg.vehicleEntityId > 0) {
                Entity vehicle = level.getEntity(msg.vehicleEntityId);
                if (vehicle == null || !SbwCompat.isOperational(vehicle)) {
                    sender.displayClientMessage(
                        Component.translatable("transport.steves_army.feedback.no_vehicle"), true);
                    return;
                }
                List<SoldierEntity> vehicleCrew = resolvePermittedCrew(level, msg.crewIds, sender, creativeCrewTool);
                if (vehicleCrew.isEmpty()) {
                    StevesArmyMod.LOGGER.info(
                        "[CrewStick] packet dropped: none of the {} ids resolved to permitted vehicle crew",
                        msg.crewIds.size());
                    return;
                }
                int seated = CrewAssignment.mountCrewOnVehicle(level, vehicle, vehicleCrew);
                StevesArmyMod.LOGGER.info("[CrewStick] vehicle assign: vehicle={} type={} crew={}/{} seated={}",
                    vehicle.getId(), SbwCompat.getVehicleTypeName(vehicle),
                    vehicleCrew.size(), msg.crewIds.size(), seated);
                sender.displayClientMessage(
                    Component.translatable("transport.steves_army.feedback.crew_assigned", seated, vehicleCrew.size()), true);
                return;
            }
            // Ship resolution in /vs get-ship order: the vanilla clip's hit block is
            // the authoritative crosshair pick, so the ship managing that exact block
            // wins; the geometric resolver only runs when the hit block maps to nothing.
            Object ship = VS2Compat.getShipObjectAtBlockPos(level, msg.hitBlock);
            if (ship != null) {
                StevesArmyMod.LOGGER.info("[CrewStick] ship resolved via vs get-ship at hit block {}: shipId={}",
                    msg.hitBlock, VS2Compat.getShipIdOf(ship));
            }
            if (ship == null) {
                ship = VS2Compat.resolveShipAtWorldAnchor(level, anchor);
            }
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
            List<SoldierEntity> crew = resolvePermittedCrew(level, msg.crewIds, sender, creativeCrewTool);
            if (crew.isEmpty()) {
                StevesArmyMod.LOGGER.info(
                    "[CrewStick] packet dropped: none of the {} ids resolved to permitted vehicle crew",
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

    /** Re-validates the client's crew ids: role, ownership, and de-duplication. */
    private static List<SoldierEntity> resolvePermittedCrew(ServerLevel level, List<Integer> crewIds,
                                                            ServerPlayer sender, boolean creativeCrewTool) {
        List<SoldierEntity> crew = new ArrayList<>();
        for (int id : crewIds) {
            Entity entity = level.getEntity(id);
            if (entity instanceof SoldierEntity soldier
                && soldier.getRole() == SoldierRole.VEHICLE_CREW
                && (creativeCrewTool || soldier.isOwnedBy(sender))
                && !crew.contains(soldier)) {
                crew.add(soldier);
            }
        }
        return crew;
    }
}
