package com.stevesarmy.squad;

import com.stevesarmy.StevesArmyConfig;
import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.debug.trace.SoldierFlightRecorder;
import com.stevesarmy.combat.VisibilityRay;
import com.stevesarmy.combat.TargetAcquisition;
import com.stevesarmy.entity.TargetEntity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.combat.cover.CoverReservationManager;
import com.stevesarmy.ping.PingType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Squad-level decision owner for ATTACK clearing orders: the order decides
 * what progress the squad needs next; cover, navigation, aiming and firing
 * systems execute that decision. After clearance it assigns GO_TO catch-up orders.
 *
 * <p>For each ATTACK {@code SquadActivityManager.Activity} it maintains a
 * bounded clearing area — a flood fill of walkable space connected to the
 * ping, capped in radius ({@code attackClearRadius}) and height (±6 blocks:
 * stairs can pull in an upper floor — this is a connected-walkable-region
 * approximation, not room recognition). Blind spots — wall-adjacent area
 * cells and the last-known positions of enemies that broke contact — become
 * check targets. Soldiers claim one each; a target is CHECKED only after
 * verified line of sight across the geometry (never proximity through
 * walls, even between two cells of the same area) has held for a short
 * dwell. The order completes as "Area secured" when a strict majority has
 * physically entered the space (strict area cells), checked its blind
 * spots, resolved known contact — a check made before the enemy's last
 * sighting does not count — without an additional settling delay. A stalled
 * attack (a full window with
 * neither a new check nor net displacement) is reported once and then
 * persists until the player replaces or cancels it; it is a report, not a
 * hold, so the squad keeps trying while it shows.
 */
public final class ClearingCoordinator {

    /** Clearing progression shown to the player and consumed by CoverTacticalGoal. */
    public enum ClearingStage {
        APPROACH, CLEAR, SECURE, SECURED, STALLED;

        public String label() {
            return switch (this) {
                case APPROACH -> "ADVANCING";
                case CLEAR -> "CLEARING";
                case SECURE -> "SECURING";
                case SECURED -> "SECURED";
                case STALLED -> "STALLED";
            };
        }
    }

    /** Per-soldier read view; computed live from coordinator state on the server thread. */
    public record ClearingView(ClearingStage stage, BlockPos assignedPoi, boolean assignedPoiChecked,
                               BlockPos nextPoi, boolean insideArea, boolean completed,
                               int checkedCount, int totalCount, boolean secureAssemble) {
        public boolean secured() {
            return stage == ClearingStage.SECURE;
        }
    }

    private static final Map<UUID, EnumMap<FireTeam, ClearingState>> STATES = new HashMap<>();
    private static final int AREA_CELL_CAP = 600;
    private static final int POI_CAP = 64;
    private static final int MAX_STEP = 6;
    private static final int RECENT_CONTACT_TICKS = 40;
    private static final int CLAIM_REFRESH_TICKS = 40;
    private static final int HEAVY_INTERVAL_TICKS = 10;
    /**
     * A check target needs this much wall-clock attention after first real
     * line of sight before it counts as observed — glancing a corner through
     * a doorway for one tick is not "checking the space".
     */
    private static final int CHECK_DWELL_TICKS = 20;
    /** A completed order stays readable (stage SECURED) this long after the handoff. */
    private static final int COMPLETION_GRACE_TICKS = 100;

    private ClearingCoordinator() {
    }

    // ------------------------------------------------------------------ state

    private static final class ClearingState {
        long generation = -1;
        BlockPos objective = BlockPos.ZERO;
        int dimension;
        ServerLevel level;
        Set<UUID> squadIds = new HashSet<>();

        Set<Long> areaCells = Set.of();
        List<BlockPos> pois = List.of();
        /** check-target key (packed position) -> game tick it was checked. */
        Map<Long, Long> checkedTick = new HashMap<>();
        /** check-target key -> {first verified-sight tick, last verified-sight tick} while dwelling. */
        Map<Long, long[]> dwellTick = new HashMap<>();
        /** Game tick of the most recent successful check (sim/report seam). */
        long lastCheckTick = -1;

        /**
         * Enemies that broke contact inside the area halo: last-known position is a check target.
         * Kept even after squad intel forgets the threat — forgetting is not resolving; the
         * obligation only clears once the last-known is observed after the final sighting.
         */
        Map<UUID, BlockPos> contactPoints = new HashMap<>();
        /** contact id -> last tick the squad actually saw that threat. */
        Map<UUID, Long> contactLastSeen = new HashMap<>();

        Map<UUID, BlockPos> claims = new HashMap<>();

        ClearingStage stage = ClearingStage.APPROACH;
        boolean stalled;
        long stallAnnouncedTick = Long.MIN_VALUE;
        /** Stall approach-anchor: centroid distance to the objective at anchor time (-1 unanchored). */
        long stallAnchorTick = -1;
        double stallAnchorDist = 0;
        long completedAtTick = Long.MIN_VALUE;
        boolean completeRequested;
        boolean regrouping;
        int lastSyncedPending = -1;
        Set<UUID> recipients = new HashSet<>();
        Set<UUID> arrived = new HashSet<>();
        Map<UUID, Integer> catchUpGenerations = new HashMap<>();
        /** TEMP DIAGNOSTIC (wave A validation): last stall-eligibility value, for transition logs. */
        boolean diagStallEligible;
    }

    // ------------------------------------------------------------------ tick

    public static void tick(MinecraftServer server) {
        if (!StevesArmyConfig.isAttackClearEnabled()) {
            STATES.clear();
            return;
        }

        List<SquadActivityManager.AttackActivityView> activities =
            SquadActivityManager.snapshotAttackActivities();
        pruneMissing(activities);

        for (SquadActivityManager.AttackActivityView activity : activities) {
            EnumMap<FireTeam, ClearingState> byTeam =
                STATES.computeIfAbsent(activity.ownerId(), ignored -> new EnumMap<>(FireTeam.class));
            ClearingState state = byTeam.computeIfAbsent(activity.fireTeam(), ignored -> new ClearingState());

            if (state.generation != activity.generation()
                || !state.objective.equals(activity.objective())) {
                rebuild(server, state, activity);
            }
            if (state.level == null) {
                continue;
            }

            state.recipients.retainAll(activity.recipientIds());
            state.arrived.retainAll(state.recipients);
            if (state.regrouping) {
                if (state.level.getGameTime() % HEAVY_INTERVAL_TICKS == 0) {
                    tickRegrouping(server, state, activity);
                }
                continue;
            }

            reissueMissingObjectives(state, activity);
            List<SoldierEntity> squad = livingRecipients(state, activity);
            if (squad.isEmpty()) {
                continue;
            }

            long gameTime = state.level.getGameTime();
            refreshObjectiveClocks(squad);
            boolean heavy = gameTime % HEAVY_INTERVAL_TICKS == 0;
            if (heavy) {
                if (state.areaCells.isEmpty()) {
                    if (gameTime % 40 == 0) computeArea(state);
                    if (state.areaCells.isEmpty()) {
                        state.stage = ClearingStage.APPROACH;
                        continue;
                    }
                }
                int previousStage = stageOrdinal(activity.ownerId(), activity.fireTeam());
                observe(state, squad);
                updateContactPoints(state, squad, gameTime);
                updateStageAndCompletion(server, state, activity, squad);
                if (!state.regrouping) tickContactPlan(state.level, squad);
                if (!state.completeRequested
                    && previousStage != stageOrdinal(activity.ownerId(), activity.fireTeam())) {
                    ServerPlayer owner = server.getPlayerList().getPlayer(activity.ownerId());
                    if (owner != null) {
                        SquadActivityManager.sync(owner);
                    }
                }
            }
            // Claims reassign on the observe cadence, not their own: consuming a
            // claim frees its owner, and a claim-less soldier falls back to a
            // per-tick nearest-unchecked pick that dithers when he stands near
            // the midpoint of two unchecked clusters.
            if (!state.regrouping && (heavy || gameTime % CLAIM_REFRESH_TICKS == 0)) {
                assignClaims(state, squad, gameTime);
            }
        }
    }

    /**
     * Order-of-record persistence: a recipient whose local attack objective
     * expired or was lost (long chunk unload — the position is not saved with
     * the entity and the memory clock keeps running) is re-issued the live
     * activity's objective through the same ping path the original order used.
     * Without this, returning soldiers silently drop out of an order that is
     * still active. Completed orders are left alone.
     *
     * Deliberately NOT a completion pull: a soldier parked in cover just
     * outside the area whose 60s target memory still reads "valid" is pulled
     * back by the SECURE-stage assemble behavior in CoverTacticalGoal (driven
     * by the view's secureAssemble flag), not by re-pinging here — a ping
     * re-runs cover selection and can re-dock him on the same sidewalk spot
     * forever.
     */
    private static void reissueMissingObjectives(ClearingState state,
                                                 SquadActivityManager.AttackActivityView activity) {
        if (state.completeRequested || state.regrouping) {
            return;
        }
        for (UUID id : activity.recipientIds()) {
            if (state.level.getEntity(id) instanceof SoldierEntity soldier
                && soldier.isAlive() && !soldier.isRemoved()
                && !soldier.hasValidAttackTarget()) {
                BlockPos obj = activity.objective();
                soldier.receivePing(com.stevesarmy.ping.PingType.ATTACK,
                    new Vec3(obj.getX() + 0.5, obj.getY(), obj.getZ() + 0.5));
            }
        }
    }

    private static void pruneMissing(List<SquadActivityManager.AttackActivityView> activities) {
        Set<String> expected = new HashSet<>();
        for (SquadActivityManager.AttackActivityView a : activities) {
            expected.add(a.ownerId() + "/" + a.fireTeam());
        }
        Iterator<Map.Entry<UUID, EnumMap<FireTeam, ClearingState>>> ownerIt = STATES.entrySet().iterator();
        while (ownerIt.hasNext()) {
            Map.Entry<UUID, EnumMap<FireTeam, ClearingState>> ownerEntry = ownerIt.next();
            ownerEntry.getValue().keySet().removeIf(team -> {
                ClearingState state = ownerEntry.getValue().get(team);
                if (state != null && state.completeRequested
                    && state.completedAtTick != Long.MIN_VALUE
                    && state.level != null
                    && state.level.getGameTime() - state.completedAtTick <= COMPLETION_GRACE_TICKS) {
                    return false;
                }
                return !expected.contains(ownerEntry.getKey() + "/" + team);
            });
            if (ownerEntry.getValue().isEmpty()) {
                ownerIt.remove();
            }
        }
    }

    private static void rebuild(MinecraftServer server, ClearingState state,
                                SquadActivityManager.AttackActivityView activity) {
        state.generation = activity.generation();
        state.objective = activity.objective();
        state.dimension = activity.dimension();
        state.checkedTick.clear();
        state.dwellTick.clear();
        state.contactPoints.clear();
        state.contactLastSeen.clear();
        state.claims.clear();
        state.stage = ClearingStage.APPROACH;
        state.stalled = false;
        state.stallAnnouncedTick = Long.MIN_VALUE;
        state.stallAnchorTick = -1;
        state.stallAnchorDist = 0;
        state.completeRequested = false;
        state.regrouping = false;
        state.lastSyncedPending = -1;
        state.recipients = new HashSet<>(activity.recipientIds());
        state.arrived.clear();
        state.catchUpGenerations.clear();
        state.completedAtTick = Long.MIN_VALUE;
        state.level = findLevel(server, state);
        if (state.level == null) {
            state.areaCells = Set.of();
            state.pois = List.of();
            return;
        }
        computeArea(state);
        // The stall clock starts from the order's birth, not from tick 0.
        state.lastCheckTick = state.level.getGameTime();
    }

    private static ServerLevel findLevel(MinecraftServer server, ClearingState state) {
        for (ServerLevel level : server.getAllLevels()) {
            if (level.dimension().location().hashCode() == state.dimension) {
                return level;
            }
        }
        return null;
    }

    private static List<SoldierEntity> livingRecipients(ClearingState state,
                                                        SquadActivityManager.AttackActivityView activity) {
        List<SoldierEntity> soldiers = new ArrayList<>();
        for (UUID id : activity.recipientIds()) {
            if (state.level.getEntity(id) instanceof SoldierEntity soldier
                && soldier.isAlive() && !soldier.isRemoved()
                && soldier.hasValidAttackTarget()) {
                soldiers.add(soldier);
            }
        }
        return soldiers;
    }

    /** The clearing order is the clock: an active order never silently expires. */
    private static void refreshObjectiveClocks(List<SoldierEntity> squad) {
        for (SoldierEntity soldier : squad) {
            soldier.refreshAttackOrderClock();
        }
    }

    // ------------------------------------------------------------------ area

    private static void computeArea(ClearingState state) {
        BlockPos start = snapToStandable(state.level, state.objective);
        if (start == null) {
            // An invalid marker is not a reachable cleared objective.
            state.areaCells = Set.of();
            state.pois = List.of();
            return;
        }

        int radius = StevesArmyConfig.getAttackClearRadius();
        long radiusSq = (long) radius * radius;
        Set<Long> cells = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        cells.add(start.asLong());
        queue.add(start);

        while (!queue.isEmpty() && cells.size() < AREA_CELL_CAP) {
            BlockPos current = queue.poll();
            for (Direction dir : Direction.Plane.HORIZONTAL) {
                for (int dy = -1; dy <= 1; dy++) {
                    BlockPos candidate = current.relative(dir).offset(0, dy, 0);
                    if (!cells.add(candidate.asLong())) {
                        continue;
                    }
                    long dx = candidate.getX() - start.getX();
                    long dz = candidate.getZ() - start.getZ();
                    if (dx * dx + dz * dz > radiusSq
                        || Math.abs(candidate.getY() - start.getY()) > MAX_STEP) {
                        cells.remove(candidate.asLong());
                        continue;
                    }
                    if (!isStandable(state.level, candidate)) {
                        cells.remove(candidate.asLong());
                        continue;
                    }
                    queue.add(candidate);
                }
            }
        }
        state.areaCells = cells;
        state.pois = collectPois(state, start, cells);
    }

    /** Wall-adjacent area cells: the corners and door shadows that conceal. */
    private static List<BlockPos> collectPois(ClearingState state, BlockPos start, Set<Long> cells) {
        List<BlockPos> pois = new ArrayList<>();
        for (Long packed : cells) {
            if (pois.size() >= POI_CAP) {
                break;
            }
            BlockPos cell = BlockPos.of(packed);
            int walls = 0;
            for (Direction dir : Direction.Plane.HORIZONTAL) {
                if (isWall(state.level, cell.relative(dir))) {
                    walls++;
                }
            }
            if (walls >= 2) {
                pois.add(cell.immutable());
            }
        }
        pois.sort(Comparator.comparingDouble(p -> p.distSqr(start)));
        return pois;
    }

    private static boolean isStandable(ServerLevel level, BlockPos cell) {
        if (!level.hasChunkAt(cell) || !level.hasChunkAt(cell.below())) return false;
        var floor = level.getBlockState(cell.below()).getCollisionShape(level, cell.below());
        if (floor.isEmpty() || !level.getFluidState(cell).isEmpty()) return false;
        double top = floor.max(Direction.Axis.Y);
        // Canonical cells are above the supporting block, even on paths/slabs.
        if (top <= 0 || top > 1) return false;
        double feet = cell.getY() - 1 + top;
        return level.noCollision(new AABB(cell.getX() + 0.2, feet + 0.001, cell.getZ() + 0.2,
            cell.getX() + 0.8, feet + 1.8, cell.getZ() + 0.8));
    }

    /** Partial-height floors put blockPosition one cell below the navigation cell. */
    private static boolean insideArea(ClearingState state, BlockPos pos) {
        if (state.areaCells.contains(pos.asLong())) return true;
        if (!state.areaCells.contains(pos.above().asLong())) return false;
        var support = state.level.getBlockState(pos).getCollisionShape(state.level, pos);
        return !support.isEmpty() && support.max(Direction.Axis.Y) > 0
            && support.max(Direction.Axis.Y) < 1;
    }

    @SuppressWarnings("deprecation")
    private static boolean isWall(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return !state.isPathfindable(level, pos, PathComputationType.LAND)
            || state.isSolidRender(level, pos);
    }

    private static BlockPos snapToStandable(ServerLevel level, BlockPos objective) {
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                if (dx * dx + dz * dz > 4) continue;
                for (int dy = -2; dy <= 4; dy++) {
                    BlockPos candidate = objective.offset(dx, dy, dz);
                    double distance = dx * dx + dz * dz + dy * dy;
                    if (distance < bestDistance && isStandable(level, candidate)) {
                        best = candidate;
                        bestDistance = distance;
                    }
                }
            }
        }
        return best;
    }

    // ----------------------------------------------------------- observation

    private static void observe(ClearingState state, List<SoldierEntity> squad) {
        long now = state.level.getGameTime();

        // Proximity is necessary but never sufficient — an interior wall can
        // hide a spot two blocks away inside the same connected area, so every
        // near check still needs verified line of sight across the geometry.
        // Either path below marks first sight; the target only counts as
        // CHECKED after CHECK_DWELL_TICKS of having been seen (glancing a
        // corner through a doorway for one tick is not checking it).
        for (SoldierEntity soldier : squad) {
            BlockPos at = soldier.blockPosition();
            if (!insideArea(state, at)) {
                continue;
            }
            for (BlockPos poi : state.pois) {
                if (state.checkedTick.containsKey(poi.asLong()) || poi.distSqr(at) > 8) {
                    continue;
                }
                noteSight(state, poi, now, sees(state, soldier, poi));
            }
            for (BlockPos contact : state.contactPoints.values()) {
                if (state.checkedTick.containsKey(contact.asLong()) || contact.distSqr(at) > 8) {
                    continue;
                }
                noteSight(state, contact, now, sees(state, soldier, contact));
            }
        }

        int budget = StevesArmyConfig.getAttackClearRayBudget();
        if (budget <= 0) {
            return;
        }
        // Unchecked targets within the awareness range, nearest first; "observed"
        // means a soldier has real line of sight, never proximity through walls.
        List<BlockPos> targets = new ArrayList<>();
        for (BlockPos poi : state.pois) {
            if (!state.checkedTick.containsKey(poi.asLong()) && nearAny(squad, poi)) {
                targets.add(poi);
            }
        }
        for (BlockPos contact : state.contactPoints.values()) {
            if (!state.checkedTick.containsKey(contact.asLong()) && nearAny(squad, contact)) {
                targets.add(contact);
            }
        }
        Vec3 centroid = Vec3.ZERO;
        for (SoldierEntity soldier : squad) {
            centroid = centroid.add(soldier.position());
        }
        centroid = centroid.scale(1.0 / squad.size());
        Vec3 centroidAt = centroid;
        targets.sort(Comparator.comparingDouble(p -> p.distSqr(BlockPos.containing(centroidAt))));

        int spent = 0;
        for (BlockPos target : targets) {
            if (spent >= budget) {
                break;
            }
            Vec3 to = Vec3.atCenterOf(target).add(0, 0.7, 0);
            for (SoldierEntity soldier : squad) {
                if (soldier.position().distanceTo(to) > StevesArmyConfig.getAttackClearAwareness()) {
                    continue;
                }
                spent++;
                VisibilityRay.Result ray = VisibilityRay.trace(
                    state.level, soldier.getEyePosition(), to, soldier);
                if (ray.clear()) {
                    noteSight(state, target, now, true);
                    break;
                }
                if (spent >= budget) {
                    break;
                }
            }
        }
    }

    /** Verified line of sight from a soldier's eye to a check target. */
    private static boolean sees(ClearingState state, SoldierEntity soldier, BlockPos target) {
        Vec3 to = Vec3.atCenterOf(target).add(0, 0.7, 0);
        return VisibilityRay.trace(state.level, soldier.getEyePosition(), to, soldier).clear();
    }

    /**
     * Records one verified sighting pass. The dwell measures TIME SPENT
     * OBSERVING, not elapsed time between glimpses: an occlusion gap longer
     * than the dwell length itself restarts the accumulation, so glimpse,
     * long occlusion, glimpse does not complete a check. Interruptions
     * shorter than the dwell length accumulate (a soldier re-tensing on a
     * peek is still watching the same corner).
     */
    private static void noteSight(ClearingState state, BlockPos target, long now, boolean clearLoS) {
        if (!clearLoS) {
            return;
        }
        long key = target.asLong();
        long[] dwell = state.dwellTick.get(key);
        if (dwell == null || now - dwell[1] > CHECK_DWELL_TICKS) {
            dwell = new long[]{now, now};
        } else {
            dwell[1] = now;
        }
        state.dwellTick.put(key, dwell);
        if (now - dwell[0] >= CHECK_DWELL_TICKS) {
            state.dwellTick.remove(key);
            check(state, target, now);
        }
    }

    private static void check(ClearingState state, BlockPos target, long now) {
        if (state.checkedTick.putIfAbsent(target.asLong(), now) == null) {
            state.lastCheckTick = now;
            noteProgress(state, now);
        }
    }

    private static boolean nearAny(List<SoldierEntity> squad, BlockPos pos) {
        long rangeSq = (long) StevesArmyConfig.getAttackClearAwareness()
            * StevesArmyConfig.getAttackClearAwareness();
        for (SoldierEntity soldier : squad) {
            if (soldier.blockPosition().distSqr(pos) <= rangeSq) {
                return true;
            }
        }
        return false;
    }

    private static void noteProgress(ClearingState state, long now) {
        state.stalled = false;
        state.stallAnnouncedTick = Long.MIN_VALUE;
        // A new observation restarts the stall clock from here (lastCheckTick
        // itself is stamped in check()).
    }

    // --------------------------------------------------------------- contact

    /**
     * Enemies that broke contact inside the area halo: their last-known
     * position becomes a check target. Losing sight of an enemy neither ends
     * the attack nor grants perfect knowledge of where he went — the squad
     * must look before the order can complete. An obligation outlives squad
     * intel: when intel forgets a threat, the coordinator keeps the contact
     * (forgetting must not substitute for investigation). Only a death
     * confirmed by intel removes an obligation outright; otherwise the
     * last-known must be observed after the final sighting.
     */
    private static void updateContactPoints(ClearingState state, List<SoldierEntity> squad, long now) {
        int haloRadius = (int) (StevesArmyConfig.getAttackClearRadius()
            + StevesArmyConfig.getAttackClearAwareness() * 0.5);
        long haloSq = (long) haloRadius * haloRadius;

        state.squadIds.clear();
        for (SoldierEntity soldier : squad) {
            if (soldier.getSquadId() != null) {
                state.squadIds.add(soldier.getSquadId());
            }
        }

        Set<UUID> deadConfirmed = new HashSet<>();
        for (UUID squadId : state.squadIds) {
            SquadManager.get(state.level).getSquadById(squadId).ifPresent(squadData -> {
                for (SquadThreatIntel.ThreatKnowledge threat : squadData.getThreatIntel().getAllThreats()) {
                    if (threat.lastKnownPosition == null) {
                        continue;
                    }
                    if (!threat.isAlive) {
                        deadConfirmed.add(threat.threatEntityId);
                        continue;
                    }
                    if (threat.lastKnownPosition.distSqr(state.objective) > haloSq) {
                        continue;
                    }
                    BlockPos known = threat.lastKnownPosition.immutable();
                    BlockPos existing = state.contactPoints.get(threat.threatEntityId);
                    // The obligation lives at the STORED last-known; small
                    // moves inside the hysteresis do not migrate it. Evidence
                    // (check / partial dwell) is only meaningful at the same
                    // position it was gathered — invalidate it there.
                    BlockPos obligation = existing != null ? existing : known;
                    if (existing == null || existing.distSqr(known) > 16) {
                        state.contactPoints.put(threat.threatEntityId, known);
                        // A migrated obligation is a new investigation target:
                        // evidence gathered at the old corner says nothing
                        // about the new one, and any check that predates this
                        // sighting at the new position says even less.
                        state.checkedTick.remove(known.asLong());
                        state.dwellTick.remove(known.asLong());
                    }
                    Long prevSeen = state.contactLastSeen.get(threat.threatEntityId);
                    state.contactLastSeen.put(threat.threatEntityId,
                        prevSeen == null ? threat.lastSeenTime : Math.max(prevSeen, threat.lastSeenTime));
                    // A fresh sighting invalidates older evidence — INCLUDING
                    // the first registration: a contact discovered at a spot
                    // the squad already checked must re-arm that check, or the
                    // obligation can never resolve (observation skips
                    // already-checked positions, and resolution demands a
                    // check newer than the sighting). A check made before the
                    // squad saw him again says nothing about where he is now.
                    // While he stays visibly there the check never lands (a
                    // live sighting blocks completion through recentContact
                    // anyway), and once sightings stop, a fresh sustained
                    // observation completes it.
                    if (prevSeen == null || threat.lastSeenTime > prevSeen) {
                        state.checkedTick.remove(obligation.asLong());
                        state.dwellTick.remove(obligation.asLong());
                    }
                }
            });
        }
        // Death is the one resolution that needs no further investigation.
        state.contactPoints.keySet().removeIf(deadConfirmed::contains);
    }

    private static boolean isContactResolved(ClearingState state, Map.Entry<UUID, BlockPos> contact,
                                             long now) {
        for (UUID squadId : state.squadIds) {
            var intel = SquadManager.get(state.level).getSquadById(squadId)
                .map(SquadData::getThreatIntel).orElse(null);
            if (intel == null) {
                continue;
            }
            for (SquadThreatIntel.ThreatKnowledge threat : intel.getAllThreats()) {
                if (threat.threatEntityId.equals(contact.getKey()) && !threat.isAlive) {
                    return true;
                }
            }
        }
        // Checked AFTER the last sighting, and the threat quiet since: the
        // squad looked where the enemy went and he was not there. A check made
        // during combat (before he broke contact) proves nothing about where
        // he ended up.
        Long checkedAt = state.checkedTick.get(contact.getValue().asLong());
        long lastSighting = lastSeen(state, contact.getKey());
        return checkedAt != null && checkedAt > lastSighting
            && now - lastSighting > RECENT_CONTACT_TICKS;
    }

    private static long lastSeen(ClearingState state, UUID threatId) {
        Long tracked = state.contactLastSeen.get(threatId);
        if (tracked != null) {
            return tracked;
        }
        for (UUID squadId : state.squadIds) {
            var intel = SquadManager.get(state.level).getSquadById(squadId)
                .map(SquadData::getThreatIntel).orElse(null);
            if (intel == null) {
                continue;
            }
            for (SquadThreatIntel.ThreatKnowledge threat : intel.getAllThreats()) {
                if (threat.threatEntityId.equals(threatId)) {
                    return threat.lastSeenTime;
                }
            }
        }
        return 0L;
    }

    // ------------------------------------------------------------------ jobs

    private static void assignClaims(ClearingState state, List<SoldierEntity> squad, long now) {
        Set<UUID> living = new HashSet<>();
        for (SoldierEntity soldier : squad) {
            living.add(soldier.getUUID());
        }
        state.claims.keySet().removeIf(id -> !living.contains(id));
        // A just-checked claim stays assigned to its owner for at least one
        // heavy observe cycle: the execution layer's "my job is done, advance"
        // trigger reads assignedPoiChecked live, and stripping the claim the
        // moment it checks (before the owner's next goal tick) makes that
        // trigger a timing race instead of a signal.
        state.claims.entrySet().removeIf(claim -> {
            Long checkedAt = state.checkedTick.get(claim.getValue().asLong());
            return checkedAt != null && now - checkedAt >= HEAVY_INTERVAL_TICKS;
        });

        Set<BlockPos> taken = new HashSet<>(state.claims.values());
        for (SoldierEntity soldier : squad) {
            if (state.claims.containsKey(soldier.getUUID())) {
                continue;
            }
            BlockPos best = nearestUnchecked(state, soldier, taken);
            if (best != null) {
                state.claims.put(soldier.getUUID(), best.immutable());
                taken.add(best);
            }
        }
        skipBehindEveryone(state, squad, now);
    }

    /** Candidates at parity (2 blocks) with the soldier's objective distance are not "behind". */
    private static final double BEHIND_SLACK_SQ = 4.0;

    /**
     * Blind spots farther from the objective than every living soldier cannot
     * be reached by advancing — holding the order open for them asks the squad
     * to walk backwards, which is exactly the stuck-behind failure this
     * coordinator must not create. Mark them checked (skipped): the doctrine
     * accepts an unobserved pocket behind the whole squad once everyone is
     * past it.
     */
    private static void skipBehindEveryone(ClearingState state, List<SoldierEntity> squad, long now) {
        boolean anyAlive = false;
        for (SoldierEntity soldier : squad) {
            if (soldier.isAlive()) {
                anyAlive = true;
                break;
            }
        }
        if (!anyAlive) {
            return;
        }
        for (BlockPos poi : state.pois) {
            long key = poi.asLong();
            if (state.checkedTick.containsKey(key) || state.claims.containsValue(poi)) {
                continue;
            }
            double poiToObj = poi.distSqr(state.objective);
            boolean behindEveryone = true;
            for (SoldierEntity soldier : squad) {
                if (soldier.isAlive()
                    && poiToObj + BEHIND_SLACK_SQ < state.objective.distSqr(soldier.blockPosition())) {
                    behindEveryone = false;
                    break;
                }
            }
            if (behindEveryone) {
                state.checkedTick.put(key, now);
            }
        }
    }

    private static BlockPos nearestUnchecked(ClearingState state, SoldierEntity soldier,
                                             Set<BlockPos> taken) {
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        // Forward preference: a check target behind the soldier (farther from
        // the objective than he already is) drags the back of the squad
        // backwards while the front advances — the stuck-in-house pin. Claim
        // the nearest FORWARD target when one exists; plain nearest remains
        // the fallback so a squad with nothing forward still works its list.
        BlockPos bestForward = null;
        double bestForwardDist = Double.MAX_VALUE;
        BlockPos objective = state.objective;
        double soldierToObj = objective.distSqr(soldier.blockPosition());
        for (BlockPos poi : state.pois) {
            if (state.checkedTick.containsKey(poi.asLong()) || taken.contains(poi)) {
                continue;
            }
            double dist = poi.distSqr(soldier.blockPosition());
            if (dist < bestDist) {
                bestDist = dist;
                best = poi;
            }
            if (poi.distSqr(objective) + BEHIND_SLACK_SQ < soldierToObj
                && dist < bestForwardDist) {
                bestForwardDist = dist;
                bestForward = poi;
            }
        }
        // Contacts stay exempt from the forward filter: a last-known behind
        // the squad is a real threat back there, not a stale corner.
        for (BlockPos contact : state.contactPoints.values()) {
            if (state.checkedTick.containsKey(contact.asLong()) || taken.contains(contact)) {
                continue;
            }
            double dist = contact.distSqr(soldier.blockPosition());
            if (dist < bestDist) {
                bestDist = dist;
                best = contact;
            }
        }
        return bestForward != null ? bestForward : best;
    }

    // -------------------------------------------------- stage and completion

    private static void updateStageAndCompletion(MinecraftServer server, ClearingState state,
                                                 SquadActivityManager.AttackActivityView activity,
                                                 List<SoldierEntity> squad) {
        long now = state.level.getGameTime();

        boolean allChecked = true;
        for (BlockPos poi : state.pois) {
            if (!state.checkedTick.containsKey(poi.asLong())) {
                allChecked = false;
                break;
            }
        }
        boolean contactResolved = true;
        boolean recentContact = false;
        for (Map.Entry<UUID, BlockPos> contact : state.contactPoints.entrySet()) {
            if (!isContactResolved(state, contact, now)) {
                contactResolved = false;
            }
            if (now - lastSeen(state, contact.getKey()) <= RECENT_CONTACT_TICKS) {
                recentContact = true;
            }
        }
        boolean areaHealth = allChecked && contactResolved && !recentContact
            && isActualEnemyAreaClear(state);

        boolean anyInside = false;
        double cx = 0;
        double cz = 0;
        for (SoldierEntity soldier : squad) {
            BlockPos at = soldier.blockPosition();
            // Strict walkable-cell membership: proximity through a wall is not entry.
            boolean inside = insideArea(state, at);
            anyInside |= inside;
            cx += at.getX();
            cz += at.getZ();
        }
        cx /= squad.size();
        cz /= squad.size();

        // Clearance is latched independently from arrival. A straggler must not
        // keep the winners attacking, nor reopen the clear when new contact appears.
        if (areaHealth) {
            state.regrouping = true;
            state.stage = ClearingStage.SECURE;
            state.stalled = false;
            state.claims.clear();
            tickRegrouping(server, state, activity);
            return;
        }

        // Squad display follows entry; individual views still own movement decisions.
        state.stage = anyInside ? ClearingStage.CLEAR : ClearingStage.APPROACH;

        // Stall: quiet for a full stall window (no new observation, no live
        // contact) AND no net approach toward the objective across a second
        // such window. The two kinds of quiet are different: a squad under
        // fire is fighting, not stuck; and a squad MARCHING toward a distant
        // objective is making progress even before any new room check becomes
        // possible — only a squad that is neither observing, engaging, nor
        // closing on the objective is actually blocked. Reported once per
        // blockage: a renewed CHECK un-latches the report (and re-arms the
        // announcement, so a later stall can be reported again), and so does
        // measurable approach — but renewed contact does not, because firing
        // at the thing that blocked the order is not progress on the order.
        // STALLED is a report, not a hold: the squad keeps pursuing its jobs
        // so a change (a door opens, suppression lifts) finds them already
        // trying.
        long lastContactActivity = 0;
        for (Long seen : state.contactLastSeen.values()) {
            lastContactActivity = Math.max(lastContactActivity, seen);
        }
        long quietFor = Math.min(now - state.lastCheckTick, now - lastContactActivity);
        boolean stallEligible = (state.stage == ClearingStage.CLEAR
            || state.stage == ClearingStage.APPROACH)
            && quietFor >= StevesArmyConfig.getAttackClearStallTicks();
        // TEMP DIAGNOSTIC (wave A validation): stall-clock visibility.
        if (stallEligible != state.diagStallEligible) {
            StevesArmyMod.LOGGER.info(
                "[AttackClear][diag] squad {} stallEligible={} quietFor={} lastCheck={}t ago lastContact={}t ago now={}",
                state.squadIds, stallEligible, quietFor,
                state.lastCheckTick < 0 ? "never" : String.valueOf(now - state.lastCheckTick),
                lastContactActivity <= 0 ? "never" : String.valueOf(now - lastContactActivity), now);
            state.diagStallEligible = stallEligible;
        }
        if (!stallEligible) {
            // Engagement or a fresh observation only invalidates the approach
            // anchor; an already-latched STALLED report stays latched (the
            // check path clears it when an observation actually lands).
            state.stallAnchorTick = -1;
        } else {
            BlockPos obj = activity.objective();
            double distNow = Math.sqrt((cx - obj.getX() - 0.5) * (cx - obj.getX() - 0.5)
                + (cz - obj.getZ() - 0.5) * (cz - obj.getZ() - 0.5));
            if (state.stallAnchorTick < 0) {
                state.stallAnchorTick = now;
                state.stallAnchorDist = distNow;
            } else if (now - state.stallAnchorTick >= StevesArmyConfig.getAttackClearStallTicks()) {
                if (state.stallAnchorDist - distNow > 1.5) {
                    // Closing on the objective — progress; re-anchor.
                    StevesArmyMod.LOGGER.info(
                        "[AttackClear][diag] squad {} stall re-anchor: progress {} -> {} over {}t (latch reset)",
                        state.squadIds, String.format("%.2f", state.stallAnchorDist),
                        String.format("%.2f", distNow), now - state.stallAnchorTick);
                    state.stallAnchorTick = now;
                    state.stallAnchorDist = distNow;
                    state.stalled = false;
                    state.stallAnnouncedTick = Long.MIN_VALUE;
                } else if (!state.stalled) {
                    state.stalled = true;
                    state.stallAnnouncedTick = now;
                    ServerPlayer owner = server.getPlayerList().getPlayer(activity.ownerId());
                    if (owner != null) {
                        owner.sendSystemMessage(Component.literal(
                            "[Squad] Attack stalled - no safe progress into the objective area."));
                    }
                    StevesArmyMod.LOGGER.info("[AttackClear] order {} {} STALLED at {}",
                        activity.ownerId(), activity.fireTeam(), activity.objective());
                }
            }
        }
    }

    /** Completion-only truth check; never reveals unseen entities to perception or targeting. */
    private static boolean isActualEnemyAreaClear(ClearingState state) {
        double x = state.objective.getX() + 0.5;
        double y = state.objective.getY();
        double z = state.objective.getZ() + 0.5;
        final int radius = 16;
        // Fail closed for missing chunks intersecting the cylinder, without loading them.
        for (int cx = net.minecraft.util.Mth.floor(x - radius) >> 4;
             cx <= (net.minecraft.util.Mth.floor(x + radius) >> 4); cx++) {
            for (int cz = net.minecraft.util.Mth.floor(z - radius) >> 4;
                 cz <= (net.minecraft.util.Mth.floor(z + radius) >> 4); cz++) {
                double dx = Math.max(Math.max(cx * 16.0 - x, x - (cx + 1) * 16.0), 0);
                double dz = Math.max(Math.max(cz * 16.0 - z, z - (cz + 1) * 16.0), 0);
                if (dx * dx + dz * dz <= radius * radius && !state.level.hasChunk(cx, cz)) {
                    return false;
                }
            }
        }
        List<SoldierEntity> observers = new ArrayList<>();
        for (UUID id : state.recipients) {
            if (state.level.getEntity(id) instanceof SoldierEntity soldier && soldier.isAlive()) {
                observers.add(soldier);
            }
        }
        if (observers.isEmpty()) return false;
        for (LivingEntity enemy : state.level.getEntitiesOfClass(LivingEntity.class,
            new AABB(x - radius, y - 6, z - radius, x + radius, y + 6, z + radius))) {
            double dx = enemy.getX() - x;
            double dz = enemy.getZ() - z;
            if (dx * dx + dz * dz > radius * radius || Math.abs(enemy.getY() - y) > 6) continue;
            for (SoldierEntity soldier : observers) {
                if (!TargetAcquisition.isValidTarget(soldier, enemy)
                    || soldier.isFriendlyTo(enemy) || !soldier.isProactiveTarget(enemy)
                    || !soldier.canAttack(enemy)) continue;
                // Same combat categories, without detection or line-of-sight requirements.
                if (enemy instanceof SoldierEntity || enemy instanceof Player
                    || (enemy instanceof TargetEntity && soldier.fofCategoryAllows(
                        FofCategory.TARGET_DUMMIES, StevesArmyConfig.shouldTargetTargetEntities()))
                    || (enemy instanceof Monster && soldier.fofCategoryAllows(
                        FofCategory.MONSTERS, StevesArmyConfig.shouldTargetMonsters()))) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Continue the same recipient roster even after the activity becomes HOLD. */
    private static void tickRegrouping(MinecraftServer server, ClearingState state,
                                      SquadActivityManager.AttackActivityView activity) {
        long now = state.level.getGameTime();
        for (UUID id : new HashSet<>(state.recipients)) {
            if (!(state.level.getEntity(id) instanceof SoldierEntity soldier)) continue;
            if (!soldier.isAlive()) {
                state.recipients.remove(id);
                state.arrived.remove(id);
                continue;
            }
            if (state.arrived.contains(id)) continue;
            Integer generation = state.catchUpGenerations.get(id);
            if (generation != null) {
                if (soldier.getPingMoveGeneration() != generation
                    || !soldier.hasGoToNavigationOwnership()) {
                    // A newer player command owns this soldier. Never reclaim it.
                    state.recipients.remove(id);
                    state.catchUpGenerations.remove(id);
                    SoldierFlightRecorder.event(soldier, "regroup", "superseded", generation);
                } else if (soldier.isGoToHolding()) {
                    // Ordinary GO_TO already performed the cover/endpoint handoff.
                    state.arrived.add(id);
                    SoldierFlightRecorder.event(soldier, "regroup", "arrived", soldier.blockPosition());
                }
                continue; // Normal GO_TO owns all path recovery and combat pauses.
            }
            if (insideArea(state, soldier.blockPosition())) {
                holdArrivedSoldier(state, soldier);
                state.arrived.add(id);
                SoldierFlightRecorder.event(soldier, "regroup", "arrived", soldier.blockPosition());
                continue;
            }
            soldier.clearAttackTarget();
            soldier.receivePing(PingType.GO_TO, Vec3.atBottomCenterOf(state.objective));
            state.catchUpGenerations.put(id, soldier.getPingMoveGeneration());
            SoldierFlightRecorder.event(soldier, "regroup", "go-to-issued", state.objective);
        }

        int pending = state.recipients.size() - state.arrived.size();
        if (!state.completeRequested && !state.recipients.isEmpty()
            && state.arrived.size() * 2 > state.recipients.size()
            && isActualEnemyAreaClear(state)) {
            state.completeRequested = true;
            state.completedAtTick = now;
            state.stage = ClearingStage.SECURED;
            completeOrder(server, state, activity);
        }
        if (pending != state.lastSyncedPending) {
            state.lastSyncedPending = pending;
            ServerPlayer owner = server.getPlayerList().getPlayer(activity.ownerId());
            if (owner != null) SquadActivityManager.sync(owner);
        }
    }

    private static void holdArrivedSoldier(ClearingState state, SoldierEntity soldier) {
        soldier.clearAttackTarget();
        soldier.clearPingMoveTarget();
        soldier.setSquadMode(SquadMode.HOLD);
        soldier.setHoldPosition(soldier.blockPosition());
        soldier.clearFormationOffset();
        soldier.getNavigation().stop();
        // Preserve occupied cover/reservations and intel; cancel only an unfinished move.
        var manager = soldier.getCoverBehaviorManager();
        if (manager.getCurrentCover() != null
            && !insideArea(state, manager.getCurrentCover().getPosition())) {
            manager.clearCover();
        }
        var target = manager.getTargetCover();
        if (target != null) {
            CoverReservationManager.release(target.getPosition(), soldier);
            manager.clearTargetCover();
        }
        if (manager.getCurrentCover() == null) {
            soldier.cancelCoverMovement();
            manager.setState(com.stevesarmy.combat.cover.CoverBehaviorManager.CoverState.NO_COVER);
        } else {
            manager.setState(com.stevesarmy.combat.cover.CoverBehaviorManager.CoverState.IN_COVER);
        }
    }

    public static boolean isRegrouping(UUID ownerId, FireTeam team, long generation) {
        ClearingState state = stateFor(ownerId, team);
        return state != null && state.generation == generation && state.regrouping;
    }

    public static int regroupingCount(UUID ownerId, FireTeam team, long generation) {
        ClearingState state = stateFor(ownerId, team);
        return state != null && state.generation == generation && state.regrouping
            ? state.recipients.size() - state.arrived.size() : 0;
    }

    public static void detachRecipients(UUID ownerId, Set<UUID> recipients) {
        EnumMap<FireTeam, ClearingState> teams = STATES.get(ownerId);
        if (teams == null) return;
        for (ClearingState state : teams.values()) {
            for (UUID id : recipients) {
                state.recipients.remove(id);
                state.arrived.remove(id);
                Integer generation = state.catchUpGenerations.remove(id);
                if (generation != null && state.level != null
                    && state.level.getEntity(id) instanceof SoldierEntity soldier) {
                    soldier.clearPingMoveTargetIfGeneration(generation);
                }
            }
        }
    }

    public static void cancelOrder(UUID ownerId, FireTeam scope) {
        EnumMap<FireTeam, ClearingState> teams = STATES.get(ownerId);
        if (teams == null) return;
        for (FireTeam team : new HashSet<>(teams.keySet())) {
            if (scope != FireTeam.ALL && scope != team) continue;
            ClearingState state = teams.remove(team);
            if (state.level == null) continue;
            for (var entry : state.catchUpGenerations.entrySet()) {
                if (state.level.getEntity(entry.getKey()) instanceof SoldierEntity soldier
                    && soldier.getPingMoveGeneration() == entry.getValue()) {
                    soldier.clearPingMoveTargetIfGeneration(entry.getValue());
                }
            }
        }
        if (teams.isEmpty()) STATES.remove(ownerId);
    }

    private static void completeOrder(MinecraftServer server, ClearingState state,
                                      SquadActivityManager.AttackActivityView activity) {
        SquadActivityManager.completeAttackToHold(activity.ownerId(), activity.fireTeam(),
            activity.objective());
        ServerPlayer owner = server.getPlayerList().getPlayer(activity.ownerId());
        if (owner != null) {
            owner.sendSystemMessage(Component.literal(
                "[Squad] Area secured. All clear - holding position."));
        }
        StevesArmyMod.LOGGER.info("[AttackClear] order {} {} secured at {} (pois={}, checked={})",
            activity.ownerId(), activity.fireTeam(), activity.objective(),
            state.pois.size(), state.checkedTick.size());
    }

    // ------------------------------------------------------------- read side

    /**
     * Contact-plan observe pass for this clearing squad: opens/refreshes the
     * one close contact and reassigns base-of-fire/checker roles. Squads may
     * span several SquadData groups; each distinct squad id ticks once.
     */
    private static void tickContactPlan(ServerLevel level, List<SoldierEntity> squad) {
        java.util.Set<UUID> seen = new java.util.HashSet<>();
        for (SoldierEntity soldier : squad) {
            UUID squadId = soldier.getSquadId();
            if (squadId == null || !seen.add(squadId)) {
                continue;
            }
            SquadManager.get(level).getSquadById(squadId)
                .ifPresent(data -> ContactPlan.tick(level, data));
        }
    }

    /** Resolves the clearing state driving this soldier, or null (legacy ATTACK path). */
    private static ClearingState stateForSoldier(SoldierEntity soldier) {
        if (!StevesArmyConfig.isAttackClearEnabled() || !soldier.hasValidAttackTarget()) {
            return null;
        }
        UUID owner = soldier.getOwnerUUID().orElse(null);
        if (owner == null) {
            return null;
        }
        EnumMap<FireTeam, ClearingState> byTeam = STATES.get(owner);
        if (byTeam == null) {
            return null;
        }
        ClearingState state = byTeam.get(soldier.getFireTeam());
        if (state == null) {
            state = byTeam.get(FireTeam.ALL);
        }
        if (state == null || state.level == null
            || state.dimension != soldier.level().dimension().location().hashCode()
            || !state.objective.equals(soldier.getAttackTargetPos())) {
            return null;
        }
        return state;
    }

    /**
     * True when pos is a walkable cell of this soldier's clearing area (false
     * without an order). Cover picking uses this so a check position is never
     * docked from outside the wall — the outside slot is within reach of the
     * blind spot but observes nothing, and the order stalls circling it.
     */
    public static boolean isInsideClearingArea(SoldierEntity soldier, BlockPos pos) {
        ClearingState state = stateForSoldier(soldier);
        return state != null && insideArea(state, pos);
    }

    /**
     * True while this soldier's clearing order has latched its STALLED report
     * (a full window with no safe progress into the objective area). The
     * contact plan reads this as an emergency bypass: an attack that is
     * stalled anyway must not also freeze every advance behind the danger-arc
     * gate — someone has to be allowed to move.
     */
    public static boolean isStalledLatched(SoldierEntity soldier) {
        ClearingState state = stateForSoldier(soldier);
        return state != null && state.stalled;
    }

    /**
     * True while this soldier's squad runs an attack-clearing order, sighted
     * enemy or not. The clearing JOB is itself a reason to move: a blind spot
     * needs investigating before anyone has seen anything in it, and gating
     * that on {@code hasValidAttackTarget} starved exactly the never-sighted
     * objectives (a quiet room still has to be walked).
     */
    public static boolean hasActiveClearing(SoldierEntity soldier) {
        return stateForSoldier(soldier) != null;
    }

    /**
     * True when target is still a live, unchecked check target of this
     * soldier's order. CoverTacticalGoal holds its chosen clearing target
     * while this stays true — a soldier standing between two unchecked
     * clusters is near-equidistant to both, and re-resolving "nearest" every
     * tick dithers his advance between them instead of walking either down.
     */
    public static boolean isTargetStillOpen(SoldierEntity soldier, BlockPos target) {
        ClearingState state = stateForSoldier(soldier);
        if (state == null || target == null) {
            return false;
        }
        if (state.checkedTick.containsKey(target.asLong())) {
            return false;
        }
        return state.pois.contains(target) || state.contactPoints.containsValue(target);
    }

    /**
     * True when pos sits within maxDistSq of a live contact's last-known
     * position. Cover picking uses this so a soldier never takes cover on top
     * of the enemy he was sent to check.
     */
    public static boolean nearLiveContact(SoldierEntity soldier, BlockPos pos, double maxDistSq) {
        ClearingState state = stateForSoldier(soldier);
        if (state == null) {
            return false;
        }
        for (BlockPos contact : state.contactPoints.values()) {
            if (contact.distSqr(pos) <= maxDistSq) {
                return true;
            }
        }
        return false;
    }

    /**
     * Fail-closed concession: the assigned soldier has proven it can neither
     * check this obligation nor advance on it (bounded failed searches, no
     * walkable route). Mark it checked so the order can settle or report
     * STALLED instead of hanging on a soldier frozen between the two — the
     * same skip precedent as {@link #skipBehindEveryone}. A later observation
     * re-arms the check through the normal renewed-obligation path. Counts as
     * progress: concessions un-latch the stall report like a real check.
     *
     * @return true when this call marked the target checked.
     */
    public static boolean concedePoi(SoldierEntity soldier, BlockPos poi) {
        ClearingState state = stateForSoldier(soldier);
        if (state == null || poi == null) {
            return false;
        }
        if (!isTargetStillOpen(soldier, poi)) {
            return false;
        }
        long now = state.level.getGameTime();
        if (state.checkedTick.putIfAbsent(poi.asLong(), now) == null) {
            state.lastCheckTick = now;
            noteProgress(state, now);
            return true;
        }
        return false;
    }

    /**
     * True when a candidate dock position has line of sight to this soldier's
     * clearing check target — a dock that cannot observe the blind spot keeps
     * the soldier busy without ever contributing a check.
     */
    public static boolean dockObservesTarget(SoldierEntity soldier, BlockPos dock, BlockPos target) {
        ClearingState state = stateForSoldier(soldier);
        if (state == null || target == null) {
            return false;
        }
        Vec3 to = Vec3.atCenterOf(target).add(0, 0.7, 0);
        Vec3 eye = Vec3.atCenterOf(dock).add(0, 1.2, 0);
        return VisibilityRay.trace(state.level, eye, to, soldier).clear();
    }

    /** Per-soldier clearing view, or null when this soldier has no clearing order. */
    public static ClearingView viewFor(SoldierEntity soldier) {
        ClearingState state = stateForSoldier(soldier);
        if (state == null) {
            return null;
        }

        long now = state.level.getGameTime();
        boolean inside = insideArea(state, soldier.blockPosition());

        BlockPos assignedPoi = state.claims.get(soldier.getUUID());
        boolean poiChecked = assignedPoi != null
            && state.checkedTick.containsKey(assignedPoi.asLong());

        ClearingStage stage;
        if (state.completeRequested) {
            stage = ClearingStage.SECURED;
        } else if (state.stalled) {
            stage = ClearingStage.STALLED;
        } else if (inside && squadAreaHealth(state, now)) {
            // SECURE is a hold INSIDE the cleared space. Health alone is not
            // enough: an area with nothing left to check reads healthy while
            // the squad is still on approach, and holding then would strand
            // the advance outside the objective forever.
            stage = ClearingStage.SECURE;
        } else {
            stage = inside ? ClearingStage.CLEAR : ClearingStage.APPROACH;
        }

        // Assemble request: every check ran and every contact is resolved, so
        // completion is armed except for squad members still outside the area
        // — the executor walks them in (see CoverTacticalGoal's assemble
        // advance). False once the order completes; grace reads as done.
        boolean secureAssemble = !state.completeRequested && squadAreaHealth(state, now);

        return new ClearingView(stage, assignedPoi, poiChecked,
            nearestUncheckedFor(state, soldier), inside,
            state.completeRequested, countChecked(state),
            state.pois.size() + state.contactPoints.size(), secureAssemble);
    }

    /** Nearest unchecked target not claimed by another soldier (null when none). */
    private static BlockPos nearestUncheckedFor(ClearingState state, SoldierEntity soldier) {
        Set<UUID> self = Set.of(soldier.getUUID());
        Set<BlockPos> takenByOthers = new HashSet<>();
        for (Map.Entry<UUID, BlockPos> claim : state.claims.entrySet()) {
            if (!self.contains(claim.getKey())) {
                takenByOthers.add(claim.getValue());
            }
        }
        BlockPos best = nearestUnchecked(state, soldier, takenByOthers);
        return best;
    }

    private static boolean squadAreaHealth(ClearingState state, long now) {
        for (BlockPos poi : state.pois) {
            if (!state.checkedTick.containsKey(poi.asLong())) {
                return false;
            }
        }
        for (Map.Entry<UUID, BlockPos> contact : state.contactPoints.entrySet()) {
            if (!isContactResolved(state, contact, now)) {
                return false;
            }
        }
        return true;
    }

    private static int countChecked(ClearingState state) {
        int count = 0;
        for (BlockPos poi : state.pois) {
            if (state.checkedTick.containsKey(poi.asLong())) {
                count++;
            }
        }
        for (BlockPos contact : state.contactPoints.values()) {
            if (state.checkedTick.containsKey(contact.asLong())) {
                count++;
            }
        }
        return count;
    }

    /** Completion handoff: true when the coordinator decided this order is done. */
    public static boolean isComplete(UUID ownerId, FireTeam team, long generation) {
        EnumMap<FireTeam, ClearingState> byTeam = STATES.get(ownerId);
        if (byTeam == null) {
            return false;
        }
        ClearingState state = byTeam.get(team);
        return state != null && state.completeRequested && state.generation == generation;
    }

    public static int stageOrdinal(UUID ownerId, FireTeam team) {
        EnumMap<FireTeam, ClearingState> byTeam = STATES.get(ownerId);
        if (byTeam == null) {
            return -1;
        }
        ClearingState state = byTeam.get(team);
        if (state == null) {
            return -1;
        }
        // state.stage is recomputed each observe pass (SECURE/CLEAR); the
        // stall is a flag on top of it, so surface it here.
        return state.stalled ? ClearingStage.STALLED.ordinal() : state.stage.ordinal();
    }

    /** Sim/report seam: fraction of blind spots and contacts checked (1.0 = all, -1 = no order). */
    public static double checkedFraction(UUID ownerId, FireTeam team) {
        ClearingState state = stateFor(ownerId, team);
        if (state == null) {
            return -1.0;
        }
        int total = state.pois.size() + state.contactPoints.size();
        return total == 0 ? 1.0 : (double) countChecked(state) / total;
    }

    /** Sim/report seam: raw check count (-1 when no clearing order exists). */
    public static int checkedCount(UUID ownerId, FireTeam team) {
        ClearingState state = stateFor(ownerId, team);
        return state == null ? -1 : countChecked(state);
    }

    /** Sim/report seam: game tick of the most recent successful check (-1 when none). */
    public static long lastCheckTick(UUID ownerId, FireTeam team) {
        ClearingState state = stateFor(ownerId, team);
        return state == null ? -1L : state.lastCheckTick;
    }

    /**
     * Sim/report seam: game tick of the most recent check of a contact's
     * last-known position (-1 when no contact was ever checked). This is the
     * direct "a surviving contact was re-observed" signal — lastCheckTick
     * also moves on blind-spot checks that never touched the contact.
     */
    public static long lastContactCheckTick(UUID ownerId, FireTeam team) {
        ClearingState state = stateFor(ownerId, team);
        if (state == null) {
            return -1L;
        }
        long latest = -1L;
        for (BlockPos contact : state.contactPoints.values()) {
            Long checked = state.checkedTick.get(contact.asLong());
            if (checked != null) {
                latest = Math.max(latest, checked);
            }
        }
        return latest;
    }

    /**
     * Sim/report seam: completion is ARMED — every check ran and every contact
     * resolved, only physical arrival remains. This is
     * the direct "all obligations resolved" signal ordering asserts use; the
     * same predicate drives the executor's SECURE assemble pull.
     */
    public static boolean completionArmed(UUID ownerId, FireTeam team) {
        ClearingState state = stateFor(ownerId, team);
        return state != null && !state.completeRequested && state.level != null
            && squadAreaHealth(state, state.level.getGameTime());
    }

    /**
     * Sim/report seam: compact one-line diagnostic — stage, open check
     * targets, and per-contact resolution state. The sim harness prints it
     * at scenario end so a starved completion can be diagnosed from the
     * report run without attaching a debugger.
     */
    public static String debugDump(UUID ownerId, FireTeam team) {
        ClearingState state = stateFor(ownerId, team);
        if (state == null) {
            return "no-state";
        }
        long now = state.level.getGameTime();
        StringBuilder sb = new StringBuilder();
        sb.append("stage=").append(state.stage).append(" stalled=").append(state.stalled)
            .append(" lastCheck=").append(now - state.lastCheckTick).append("t ago");
        for (BlockPos poi : state.pois) {
            if (!state.checkedTick.containsKey(poi.asLong())) {
                sb.append(" openPoi=").append(poi.toShortString());
            }
        }
        for (Map.Entry<UUID, BlockPos> contact : state.contactPoints.entrySet()) {
            Long checked = state.checkedTick.get(contact.getValue().asLong());
            sb.append(" contact=").append(contact.getValue().toShortString())
                .append(" checked=").append(checked != null)
                .append(" lastSeen=").append(now - lastSeen(state, contact.getKey())).append("t ago");
        }
        return sb.toString();
    }

    public static void clearAll() {
        STATES.clear();
    }

    private static ClearingState stateFor(UUID ownerId, FireTeam team) {
        EnumMap<FireTeam, ClearingState> byTeam = STATES.get(ownerId);
        return byTeam == null ? null : byTeam.get(team);
    }
}
