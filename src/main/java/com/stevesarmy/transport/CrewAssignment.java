package com.stevesarmy.transport;

import com.stevesarmy.StevesArmyConfig;
import com.stevesarmy.compat.AnalogWarfareCompat;
import com.stevesarmy.compat.VS2Compat;
import com.stevesarmy.combat.StationGunnerAI;
import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.entity.SoldierRole;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Shared server-side logic for putting vehicle crew on ships: the wheel's
 * MOUNT_CREW order, the crew assign stick, the egg-on-seat shortcut, and the
 * public {@link com.stevesarmy.api.VehicleCrewApi} all route through here.
 */
public final class CrewAssignment {
    private CrewAssignment() {}

    /** Owned crew who are on foot, near the anchor, and not currently manning a station. */
    public static List<SoldierEntity> stationlessCrewNear(ServerLevel level, ServerPlayer owner,
                                                          Vec3 anchor, double radius) {
        return level.getEntitiesOfClass(SoldierEntity.class,
            new AABB(anchor, anchor).inflate(radius),
            s -> s.isOwnedBy(owner) && s.getRole() == SoldierRole.VEHICLE_CREW
                && !s.isPassenger() && !StationGunnerAI.soldierHasStation(s));
    }

    /**
     * Free Create seat entities on the same ship as {@code clickedSeat}, clicked seat
     * first and the rest ordered nearest-first, capped at {@code max}.
     */
    public static List<Entity> freeSeatsOnShip(ServerLevel level, Entity clickedSeat, int max) {
        Long shipId = shipIdOf(clickedSeat);
        if (shipId == null) {
            return new ArrayList<>();
        }
        List<Entity> candidates = new ArrayList<>();
        for (Entity entity : level.getAllEntities()) {
            if (entity == clickedSeat || entity.isRemoved() || !entity.getPassengers().isEmpty()) {
                continue;
            }
            if (!VS2Compat.isCreateSeatEntity(entity)) {
                continue;
            }
            Long other = shipIdOf(entity);
            if (other == null || !other.equals(shipId)) {
                continue;
            }
            candidates.add(entity);
        }
        Vec3 origin = VS2Compat.getSeatWorldPosition(clickedSeat);
        candidates.sort(Comparator.comparingDouble(
            seat -> VS2Compat.getSeatWorldPosition(seat).distanceToSqr(origin)));
        List<Entity> seats = new ArrayList<>(Math.min(max, candidates.size() + 1));
        seats.add(clickedSeat);
        for (Entity seat : candidates) {
            if (seats.size() >= max) {
                break;
            }
            seats.add(seat);
        }
        return seats;
    }

    /**
     * Crew assign stick: seat the selected crew on the clicked seat, then the ship's
     * other free seats. Crew actively manning a station are explicitly pulled off —
     * selection is a deliberate reassignment. Overflow crew are left where they are.
     */
    public static int assignToClickedSeat(ServerLevel level, Entity clickedSeat, List<SoldierEntity> crew) {
        List<Entity> seats = freeSeatsOnShip(level, clickedSeat, crew.size());
        int seated = 0;
        for (SoldierEntity soldier : crew) {
            if (seats.isEmpty()) {
                break;
            }
            Entity seat = seats.remove(0);
            if (reseat(soldier, seat)) {
                seated++;
            }
        }
        return seated;
    }

    /** Releases current duty/seating without leaving the crew in a reboard-blocked state, then seats it. */
    public static boolean reseat(SoldierEntity crew, Entity seat) {
        StationGunnerAI.deactivateForSoldier(crew);
        if (crew.isPassenger()) {
            VS2Compat.releaseTransport(crew);
        }
        crew.getNavigation().stop();
        // seatSoldierOnSeatEntity clears the reboard block on success, so duty
        // assignment (tickSeatedCrewDuty) can claim a station immediately.
        return VS2Compat.seatSoldierOnSeatEntity(crew, seat);
    }

    /**
     * Wheel/API path: seat nearby station-less crew on the given ship, VSAW handle
     * links first, static Create seat blocks as fallback. Mirrors the infantry MOUNT
     * seating machinery; {@code tickSeatedCrewDuty} handles station claims.
     */
    public static int autoAssignNear(ServerPlayer owner, ServerLevel level, Object ship, Vec3 anchor) {
        List<SoldierEntity> crew = stationlessCrewNear(level, owner, anchor, 64);
        if (crew.isEmpty()) {
            return 0;
        }
        List<SoldierEntity> remaining = new ArrayList<>(crew);
        int seated = 0;
        if (StevesArmyConfig.VEHICLE_HANDLES_ENABLED.get() && AnalogWarfareCompat.isAvailable()) {
            seated += AnalogWarfareCompat.mountViaHandles(level, ship, anchor, remaining);
        }
        if (!remaining.isEmpty()) {
            List<BlockPos> seats = VS2Compat.findFreeStaticSeats(level, ship, anchor, remaining.size());
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
        return seated;
    }

    @Nullable
    private static Long shipIdOf(Entity seat) {
        Object ship = VS2Compat.getShipUnder(seat);
        return ship == null ? null : VS2Compat.getShipIdOf(ship);
    }
}
