package com.stevesarmy.network;

import com.stevesarmy.StevesArmyConfig;
import com.stevesarmy.compat.AnalogWarfareCompat;
import com.stevesarmy.compat.VS2Compat;
import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.squad.FireTeam;
import com.stevesarmy.squad.SquadTargeting;
import com.stevesarmy.transport.TransportOrder;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** Vehicle wheel order: seat the scoped soldiers on a vehicle, or release them from theirs. */
public class TransportOrderMessage {
    private final TransportOrder order;
    private final double x;
    private final double y;
    private final double z;
    private final FireTeam scope;

    public TransportOrderMessage(TransportOrder order, Vec3 aimPosition, FireTeam scope) {
        this.order = order;
        this.x = aimPosition.x;
        this.y = aimPosition.y;
        this.z = aimPosition.z;
        this.scope = scope;
    }

    public TransportOrderMessage(FriendlyByteBuf buf) {
        this.order = buf.readEnum(TransportOrder.class);
        this.x = buf.readDouble();
        this.y = buf.readDouble();
        this.z = buf.readDouble();
        this.scope = buf.readEnum(FireTeam.class);
    }

    public static void encode(TransportOrderMessage msg, FriendlyByteBuf buf) {
        buf.writeEnum(msg.order);
        buf.writeDouble(msg.x);
        buf.writeDouble(msg.y);
        buf.writeDouble(msg.z);
        buf.writeEnum(msg.scope);
    }

    public TransportOrder getOrder() { return order; }
    public Vec3 getAimPosition() { return new Vec3(x, y, z); }
    public FireTeam getScope() { return scope; }

    public static void handle(TransportOrderMessage msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer sender = ctx.get().getSender();
            if (sender == null) return;

            ServerLevel level = sender.serverLevel();
            List<SoldierEntity> soldiers = SquadTargeting.resolveOrderedSoldiers(level, sender, msg.getScope());

            switch (msg.getOrder()) {
                case MOUNT -> handleMount(sender, level, soldiers, msg.getAimPosition());
                case DISMOUNT -> handleDismount(sender, soldiers);
            }
        });
        ctx.get().setPacketHandled(true);
    }

    private static void handleDismount(ServerPlayer sender, List<SoldierEntity> soldiers) {
        if (soldiers.isEmpty()) {
            sender.displayClientMessage(Component.translatable("transport.steves_army.feedback.no_soldiers"), true);
            return;
        }
        boolean handlesAvailable = StevesArmyConfig.VEHICLE_HANDLES_ENABLED.get()
            && AnalogWarfareCompat.isAvailable();
        int dismounted = 0;
        for (SoldierEntity soldier : soldiers) {
            Entity vehicle = soldier.isPassenger() ? soldier.getVehicle() : null;
            net.minecraft.world.level.block.entity.BlockEntity handle = null;
            if (handlesAvailable && vehicle != null && soldier.level() instanceof ServerLevel serverLevel) {
                handle = AnalogWarfareCompat.findHandleForSoldier(serverLevel, soldier);
            }
            if (VS2Compat.releaseTransport(soldier)) {
                dismounted++;
                if (handle != null) {
                    // Linked seat: the soldier exits at the handle (the hatch)
                    // and may stand aboard briefly before ship extraction resumes.
                    if (AnalogWarfareCompat.teleportSoldierToHandle(soldier, handle)) {
                        AnalogWarfareCompat.forget(soldier.getUUID());
                    }
                }
            }
        }
        if (dismounted == 0) {
            sender.displayClientMessage(Component.translatable("transport.steves_army.feedback.none_mounted"), true);
        } else {
            sender.displayClientMessage(
                Component.translatable("transport.steves_army.feedback.dismounted", dismounted), true);
        }
    }

    private static void handleMount(ServerPlayer sender, ServerLevel level,
                                    List<SoldierEntity> soldiers, Vec3 aimPosition) {
        if (!VS2Compat.isEnabled()) {
            sender.displayClientMessage(Component.translatable("transport.steves_army.feedback.vs2_unavailable"), true);
            return;
        }
        if (soldiers.isEmpty()) {
            sender.displayClientMessage(Component.translatable("transport.steves_army.feedback.no_soldiers"), true);
            return;
        }
        List<SoldierEntity> eligible = soldiers.stream()
            .filter(s -> !s.isPassenger())
            .toList();
        if (eligible.isEmpty()) {
            sender.displayClientMessage(Component.translatable("transport.steves_army.feedback.all_mounted"), true);
            return;
        }

        // Crosshair ship first; otherwise the ship the player rides, else the nearest ship.
        Vec3 searchCenter = aimPosition;
        Object ship = VS2Compat.getShipAt(level, BlockPos.containing(aimPosition));
        if (ship == null) {
            searchCenter = sender.position();
            ship = VS2Compat.resolveMountShipNearPlayer(level, sender);
        }
        if (ship == null) {
            sender.displayClientMessage(Component.translatable("transport.steves_army.feedback.no_vehicle"), true);
            return;
        }

        // Crew soldiers board shipyard seats exactly like riflemen; their crew AI
        // keeps ticking once seated (crewSeated), so they still man their station.
        List<SoldierEntity> remaining = new ArrayList<>(eligible);
        int seated = 0;

        // Handle-linked seats first (VS Analog Warfare vehicle mount handles);
        // soldiers without a free link fall back to the plain free-seat scan.
        if (StevesArmyConfig.VEHICLE_HANDLES_ENABLED.get() && AnalogWarfareCompat.isAvailable()) {
            seated += AnalogWarfareCompat.mountViaHandles(level, ship, searchCenter, remaining);
        }

        if (!remaining.isEmpty()) {
            List<BlockPos> seats = VS2Compat.findFreeStaticSeats(level, ship, searchCenter, remaining.size());
            for (SoldierEntity soldier : remaining) {
                if (seats.isEmpty()) {
                    break;
                }
                soldier.getNavigation().stop();
                soldier.cancelCoverMovement();
                soldier.setDeltaMovement(Vec3.ZERO);
                if (VS2Compat.seatSoldierDirect(soldier, level, seats.get(0))) {
                    seats.remove(0);
                    seated++;
                }
            }
        }

        if (seated == 0) {
            sender.displayClientMessage(Component.translatable("transport.steves_army.feedback.no_free_seats"), true);
        } else {
            sender.displayClientMessage(
                Component.translatable("transport.steves_army.feedback.seated", seated, eligible.size()), true);
        }
    }
}
