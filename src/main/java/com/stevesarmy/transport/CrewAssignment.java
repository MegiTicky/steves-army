package com.stevesarmy.transport;

import com.stevesarmy.StevesArmyConfig;
import com.stevesarmy.StevesArmyMod;
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
 * Shared server-side logic for putting vehicle crew on ships: the crew assign stick,
 * the egg-on-seat shortcut, the wheel's MOUNT_CREW order, and the public
 * {@link com.stevesarmy.api.VehicleCrewApi} all route through {@link #mountCrewOnShip},
 * which mirrors the proven infantry MOUNT seating machinery
 * ({@code TransportOrderMessage.handleMount}): static Create SeatBlocks mounted via
 * SeatBlock.sitDown first, VSAW handle links next, and the raw seat-entity mount only
 * as a last-resort fallback. A raw mount onto an arbitrary pre-existing Create
 * SeatEntity does not stick — SeatEntity.tick discards any seat that is unoccupied or
 * whose block-space query fails, so free pre-existing seats are ephemeral.
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
     * Crew assign stick / egg-on-seat: seat the selected crew on the clicked seat
     * first, then the ship's other free seats. Crew actively manning a station are
     * pulled off — selection is a deliberate reassignment. Overflow crew stay put.
     */
    public static int assignToClickedSeat(ServerLevel level, Entity clickedSeat, List<SoldierEntity> crew) {
        return mountCrewOnShip(level, VS2Compat.getShipUnder(clickedSeat),
            VS2Compat.getSeatWorldPosition(clickedSeat), clickedSeat, crew);
    }

    /**
     * Wheel/API path: seat nearby station-less crew on the given ship, using the same
     * seating machinery as the infantry MOUNT order; {@code tickSeatedCrewDuty} handles
     * station claims once seated.
     */
    public static int autoAssignNear(ServerPlayer owner, ServerLevel level, Object ship, Vec3 anchor) {
        List<SoldierEntity> crew = stationlessCrewNear(level, owner, anchor, 64);
        if (crew.isEmpty()) {
            return 0;
        }
        return mountCrewOnShip(level, ship, anchor, null, crew);
    }

    /**
     * Tiered mount routine shared by every crew entry point, in the same order as the
     * infantry MOUNT order:
     * <ol>
     *   <li>the clicked seat's static SeatBlock (SeatBlock.sitDown — the proven path),</li>
     *   <li>VSAW handle-linked seats,</li>
     *   <li>the ship-wide free SeatBlock scan around the anchor,</li>
     *   <li>raw seat-entity mounts for ships whose seats have no SeatBlock behind them
     *       (e.g. tallyho FlexibleSeatEntity) — the unreliable kind, so WARN-logged.</li>
     * </ol>
     *
     * @return the number of crew seated
     */
    public static int mountCrewOnShip(ServerLevel level, @Nullable Object ship, Vec3 anchorWorld,
                                      @Nullable Entity preferredSeat, List<SoldierEntity> crew) {
        if (crew.isEmpty()) {
            return 0;
        }
        Long shipId = VS2Compat.getShipIdOf(ship);
        List<SoldierEntity> remaining = new ArrayList<>(crew);
        int seatedCount = 0;

        // Tier 1: the clicked seat's static SeatBlock takes the first crew member.
        if (preferredSeat != null && shipId != null
            && VS2Compat.isValidStaticSeat(level, ship, preferredSeat.blockPosition())) {
            SoldierEntity soldier = remaining.remove(0);
            if (seatStatic(level, ship, soldier, preferredSeat.blockPosition(), "clicked-seat")) {
                seatedCount++;
            } else {
                remaining.add(0, soldier);
            }
        }

        // Tier 2: handle-linked seats (mountViaHandles removes the soldiers it seats).
        if (!remaining.isEmpty() && shipId != null
            && StevesArmyConfig.VEHICLE_HANDLES_ENABLED.get() && AnalogWarfareCompat.isAvailable()) {
            seatedCount += AnalogWarfareCompat.mountViaHandles(level, ship, anchorWorld, remaining);
        }

        // Tier 3: ship-wide free SeatBlock scan around the anchor.
        if (!remaining.isEmpty() && shipId != null) {
            List<BlockPos> seats = VS2Compat.findFreeStaticSeats(level, ship, anchorWorld, remaining.size());
            for (SoldierEntity soldier : new ArrayList<>(remaining)) {
                if (seats.isEmpty()) {
                    break;
                }
                BlockPos seat = seats.get(0);
                if (seatStatic(level, ship, soldier, seat, "static-scan")) {
                    seats.remove(0);
                    remaining.remove(soldier);
                    seatedCount++;
                }
            }
        }

        // Tier 4: raw seat-entity mounts — ships whose seats have no SeatBlock behind
        // them (e.g. tallyho FlexibleSeatEntity) have no static path at all.
        if (!remaining.isEmpty()) {
            List<Entity> seatEntities = freeSeatEntitiesOnShip(level, shipId, preferredSeat,
                anchorWorld, remaining.size());
            for (SoldierEntity soldier : new ArrayList<>(remaining)) {
                if (seatEntities.isEmpty()) {
                    break;
                }
                Entity seat = seatEntities.remove(0);
                prepareForMount(soldier);
                if (VS2Compat.seatSoldierOnSeatEntity(soldier, seat)) {
                    remaining.remove(soldier);
                    seatedCount++;
                    StevesArmyMod.LOGGER.info("[Crew] seated soldier={} via raw seat entity={} shipId={} (fallback tier)",
                        soldier.getId(), seat.getId(), shipId);
                }
            }
            if (!remaining.isEmpty()) {
                StevesArmyMod.LOGGER.warn("[Crew] {} of {} crew could not be seated on shipId={}: static seats free={} seat entities left={}",
                    remaining.size(), crew.size(), shipId,
                    shipId == null ? "ship-unresolved" : "none-in-reach", seatEntities.size());
            }
        }

        return seatedCount;
    }

    /** Seat one crew member on a static SeatBlock via SeatBlock.sitDown (the infantry MOUNT path). */
    private static boolean seatStatic(ServerLevel level, Object ship, SoldierEntity soldier,
                                      BlockPos seatPos, String tier) {
        prepareForMount(soldier);
        if (VS2Compat.seatSoldierDirect(soldier, level, seatPos)) {
            StevesArmyMod.LOGGER.info("[Crew] seated soldier={} via {} at seat={} shipId={}",
                soldier.getId(), tier, seatPos, VS2Compat.getShipIdOf(ship));
            return true;
        }
        return false;
    }

    /** Stops duty AI and any current transport so the mount starts from a clean state. */
    private static void prepareForMount(SoldierEntity soldier) {
        StationGunnerAI.deactivateForSoldier(soldier);
        if (soldier.isPassenger()) {
            VS2Compat.releaseTransport(soldier);
        }
        soldier.getNavigation().stop();
        soldier.cancelCoverMovement();
        soldier.setDeltaMovement(Vec3.ZERO);
    }

    /**
     * Free Create seat entities on the ship with {@code shipId}, ordered nearest-first
     * from {@code originWorld}, capped at {@code max}. With no resolvable ship, only the
     * clicked seat itself is offered (the old assign-stick behavior on orphan seats).
     */
    private static List<Entity> freeSeatEntitiesOnShip(ServerLevel level, @Nullable Long shipId,
                                                       @Nullable Entity clickedSeat, Vec3 originWorld, int max) {
        List<Entity> candidates = new ArrayList<>();
        if (shipId != null) {
            for (Entity entity : level.getAllEntities()) {
                if (entity.isRemoved() || !entity.getPassengers().isEmpty()
                    || !VS2Compat.isCreateSeatEntity(entity)) {
                    continue;
                }
                Long other = VS2Compat.getShipIdOf(VS2Compat.getShipUnder(entity));
                if (!shipId.equals(other)) {
                    continue;
                }
                candidates.add(entity);
            }
            candidates.sort(Comparator.comparingDouble(
                seat -> VS2Compat.getSeatWorldPosition(seat).distanceToSqr(originWorld)));
        } else if (clickedSeat != null && !clickedSeat.isRemoved() && clickedSeat.getPassengers().isEmpty()) {
            candidates.add(clickedSeat);
        }
        if (candidates.size() > max) {
            candidates.subList(max, candidates.size()).clear();
        }
        return candidates;
    }
}
