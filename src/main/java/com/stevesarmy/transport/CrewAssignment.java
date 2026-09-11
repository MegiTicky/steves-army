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
 * ({@code TransportOrderMessage.handleMount}). Everything is keyed by ship + anchor
 * position — ships hold SeatBlocks, not seat entities (Create discards an empty seat's
 * entity), so a raw seat-entity mount exists only as the tallyho fallback.
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
     * Wheel/API path: seat nearby station-less crew on the given ship. Mirrors the
     * infantry MOUNT seating machinery; {@code tickSeatedCrewDuty} handles station
     * claims once seated.
     */
    public static int autoAssignNear(ServerPlayer owner, ServerLevel level, Object ship, Vec3 anchor) {
        List<SoldierEntity> crew = stationlessCrewNear(level, owner, anchor, 64);
        if (crew.isEmpty()) {
            return 0;
        }
        return mountCrewOnShip(level, ship, anchor, crew);
    }

    /**
     * Tiered mount routine shared by every crew entry point, in the same order as the
     * infantry MOUNT order:
     * <ol>
     *   <li>VSAW handle-linked seats,</li>
     *   <li>the ship's free SeatBlocks, scanned across the whole voxel AABB in shipyard
     *       space and taken nearest-to-anchor first, so aiming straight at a seat
     *       prefers it,</li>
     *   <li>raw seat-entity mounts for ships whose seats have no SeatBlock behind them
     *       (e.g. tallyho FlexibleSeatEntity) — the unreliable kind, so WARN-logged.</li>
     * </ol>
     *
     * @return the number of crew seated
     */
    public static int mountCrewOnShip(ServerLevel level, @Nullable Object ship, Vec3 anchorWorld,
                                      List<SoldierEntity> crew) {
        if (crew.isEmpty()) {
            return 0;
        }
        Long shipId = VS2Compat.getShipIdOf(ship);
        if (shipId == null) {
            StevesArmyMod.LOGGER.warn("[Crew] cannot seat {} crew: ship unresolved at anchor {}",
                crew.size(), anchorWorld);
            return 0;
        }
        VS2Compat.logCrewMountDiagnostics(level, ship, anchorWorld);
        List<SoldierEntity> remaining = new ArrayList<>(crew);
        int seatedCount = 0;

        // Tier 1: handle-linked seats (mountViaHandles removes the soldiers it seats).
        if (StevesArmyConfig.VEHICLE_HANDLES_ENABLED.get() && AnalogWarfareCompat.isAvailable()) {
            seatedCount += AnalogWarfareCompat.mountViaHandles(level, ship, anchorWorld, remaining);
        }

        // Tier 2: Create's own contraption seats via the Create Interactive ship mapping —
        // the same path normal soldiers take on moving ships; no shipyard block access.
        if (!remaining.isEmpty()) {
            for (SoldierEntity soldier : new ArrayList<>(remaining)) {
                if (VS2Compat.seatSoldierOnShipContraption(level, shipId, soldier) != null) {
                    remaining.remove(soldier);
                    seatedCount++;
                }
            }
        }

        // Tier 3: the ship's free SeatBlocks, scanning the ship's whole voxel AABB
        // (shipyard space), nearest to the anchor first.
        if (!remaining.isEmpty()) {
            List<BlockPos> seats = VS2Compat.findFreeStaticSeats(level, ship, anchorWorld, remaining.size());
            if (seats.isEmpty()) {
                // No seat blocks anywhere on the ship — inventory its entities so the
                // log identifies any entity-based seats a block scan cannot see.
                VS2Compat.logShipEntityCensus(level, ship, anchorWorld);
            }
            for (SoldierEntity soldier : new ArrayList<>(remaining)) {
                if (seats.isEmpty()) {
                    break;
                }
                BlockPos seat = seats.get(0);
                if (seatStatic(level, ship, soldier, seat)) {
                    seats.remove(0);
                    remaining.remove(soldier);
                    seatedCount++;
                }
            }
        }

        // Tier 4: raw seat-entity mounts — ships whose seats have no SeatBlock behind
        // them (e.g. tallyho FlexibleSeatEntity) have no static path at all.
        if (!remaining.isEmpty()) {
            List<Entity> seatEntities = freeSeatEntitiesOnShip(level, shipId, anchorWorld, remaining.size());
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
                StevesArmyMod.LOGGER.warn(
                    "[Crew] {} of {} crew could not be seated on shipId={} at anchor {}: no free handle/static seats, {} raw seat entities in reach",
                    remaining.size(), crew.size(), shipId, anchorWorld, seatEntities.size());
            }
        }

        return seatedCount;
    }

    /** Seat one crew member on a static SeatBlock via SeatBlock.sitDown (the infantry MOUNT path). */
    private static boolean seatStatic(ServerLevel level, Object ship, SoldierEntity soldier,
                                      BlockPos seatPos) {
        prepareForMount(soldier);
        if (VS2Compat.seatSoldierDirect(soldier, level, seatPos)) {
            StevesArmyMod.LOGGER.info("[Crew] seated soldier={} via static seat={} shipId={}",
                soldier.getId(), seatPos, VS2Compat.getShipIdOf(ship));
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
     * from {@code originWorld}, capped at {@code max}. Persistent tallyho
     * FlexibleSeatEntity seats live here; unoccupied Create seats never do (their
     * entity self-discards).
     */
    private static List<Entity> freeSeatEntitiesOnShip(ServerLevel level, Long shipId,
                                                       Vec3 originWorld, int max) {
        List<Entity> candidates = new ArrayList<>();
        int createSeatEntitiesSeen = 0;
        for (Entity entity : level.getAllEntities()) {
            if (entity.isRemoved() || !VS2Compat.isCreateSeatEntity(entity)) {
                continue;
            }
            createSeatEntitiesSeen++;
            if (!entity.getPassengers().isEmpty()) {
                continue;
            }
            Long other = VS2Compat.getShipIdOf(VS2Compat.getShipUnder(entity));
            if (!shipId.equals(other)) {
                continue;
            }
            candidates.add(entity);
        }
        StevesArmyMod.LOGGER.info("[Crew] raw seat entity scan: create-seat entities in world={} free on shipId={}={}",
            createSeatEntitiesSeen, shipId, candidates.size());
        candidates.sort(Comparator.comparingDouble(
            seat -> VS2Compat.getSeatWorldPosition(seat).distanceToSqr(originWorld)));
        if (candidates.size() > max) {
            candidates.subList(max, candidates.size()).clear();
        }
        return candidates;
    }
}
