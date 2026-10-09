package com.stevesarmy.squad;

import com.stevesarmy.StevesArmyConfig;
import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.combat.VisibilityRay;
import com.stevesarmy.combat.cover.CoverBehaviorManager;
import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.entity.ai.ExposedCombatController;
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
 * systems execute that decision. This class never moves anyone.
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
 * dwell. The order completes as "Area secured" only when the squad has
 * physically entered the space (strict area cells), checked its blind
 * spots, resolved known contact — a check made before the enemy's last
 * sighting does not count — and stayed settled throughout the settle
 * window — never on proximity alone. A stalled attack (a full window with
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
     * A soldier whose navigation never reports done but who stands still this
     * many consecutive observe samples (≈40t) counts as settled — vanilla
     * paths can end a fraction short of the goal and never fire isDone, which
     * would otherwise block the SECURE settle forever.
     */
    private static final int NAV_ZOMBIE_SETTLE_SAMPLES = 4;
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
        long settledSinceTick = -1;
        long stallAnnouncedTick = Long.MIN_VALUE;
        /** Stall approach-anchor: centroid distance to the objective at anchor time (-1 unanchored). */
        long stallAnchorTick = -1;
        double stallAnchorDist = 0;
        long completedAtTick = Long.MIN_VALUE;
        boolean completeRequested;
        /** Zombie-path settle: last position of a soldier whose navigation is stuck "in progress". */
        final Map<UUID, double[]> navStillPos = new HashMap<>();
        /** Zombie-path settle: consecutive stationary observe samples per such soldier. */
        final Map<UUID, Integer> navStillTicks = new HashMap<>();
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

            reissueMissingObjectives(state, activity);
            List<SoldierEntity> squad = livingRecipients(state, activity);
            if (squad.isEmpty()) {
                continue;
            }

            long gameTime = state.level.getGameTime();
            refreshObjectiveClocks(squad);
            boolean heavy = gameTime % HEAVY_INTERVAL_TICKS == 0;
            if (heavy) {
                observe(state, squad);
                updateContactPoints(state, squad, gameTime);
                updateStageAndCompletion(server, state, activity, squad);
                tickContactPlan(state.level, squad);
            }
            // Claims reassign on the observe cadence, not their own: consuming a
            // claim frees its owner, and a claim-less soldier falls back to a
            // per-tick nearest-unchecked pick that dithers when he stands near
            // the midpoint of two unchecked clusters.
            if (heavy || gameTime % CLAIM_REFRESH_TICKS == 0) {
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
        if (state.completeRequested) {
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
        state.settledSinceTick = -1;
        state.stallAnnouncedTick = Long.MIN_VALUE;
        state.stallAnchorTick = -1;
        state.stallAnchorDist = 0;
        state.completeRequested = false;
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
            // Unreachable interior: the objective block itself is the only cell,
            // so completion degrades to "reached the marker" rather than nothing.
            state.areaCells = Set.of(state.objective.asLong());
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

    @SuppressWarnings("deprecation")
    private static boolean isStandable(ServerLevel level, BlockPos cell) {
        BlockState floor = level.getBlockState(cell.below());
        if (!floor.isFaceSturdy(level, cell.below(), Direction.UP)) {
            return false;
        }
        return level.getBlockState(cell).isPathfindable(level, cell, PathComputationType.LAND)
            && level.getBlockState(cell.above()).isPathfindable(level, cell.above(), PathComputationType.LAND);
    }

    @SuppressWarnings("deprecation")
    private static boolean isWall(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return !state.isPathfindable(level, pos, PathComputationType.LAND)
            || state.isSolidRender(level, pos);
    }

    private static BlockPos snapToStandable(ServerLevel level, BlockPos objective) {
        if (isStandable(level, objective)) {
            return objective.immutable();
        }
        for (int dy = -2; dy <= 4; dy++) {
            BlockPos candidate = objective.offset(0, dy, 0);
            if (isStandable(level, candidate)) {
                return candidate;
            }
        }
        return null;
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
            if (!state.areaCells.contains(at.asLong())) {
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
    }

    private static BlockPos nearestUnchecked(ClearingState state, SoldierEntity soldier,
                                             Set<BlockPos> taken) {
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (BlockPos poi : state.pois) {
            if (state.checkedTick.containsKey(poi.asLong()) || taken.contains(poi)) {
                continue;
            }
            double dist = poi.distSqr(soldier.blockPosition());
            if (dist < bestDist) {
                bestDist = dist;
                best = poi;
            }
        }
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
        return best;
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
        boolean areaHealth = allChecked && contactResolved && !recentContact;

        boolean allInside = true;
        boolean allSettled = true;
        double cx = 0;
        double cz = 0;
        for (SoldierEntity soldier : squad) {
            BlockPos at = soldier.blockPosition();
            // Strict: a feet block that is not a walkable area cell is outside,
            // however close. A 3-block tolerance read the sidewalk through the
            // wall as "inside" and could complete a clearing without entry.
            if (!state.areaCells.contains(at.asLong())) {
                allInside = false;
            }
            CoverBehaviorManager.CoverState coverState = soldier.getCoverBehaviorManager().getState();
            boolean coverWalking = coverState == CoverBehaviorManager.CoverState.SEEKING_COVER
                || coverState == CoverBehaviorManager.CoverState.REPOSITIONING
                || ExposedCombatController.hasActiveMove(soldier);
            boolean navWalking = !soldier.getNavigation().isDone();
            // A zombie path — navigation "in progress" while the soldier
            // stands still — used to block the settle window forever: a dock
            // walk that ends a fraction short of an occupied slot leaves an
            // unfinished path, allSettled never reads true, and an order with
            // every check done and everyone parked never completes (the
            // house-stuck completion form). Standing still for a full stall
            // of observe cycles IS settled however the path object feels;
            // only genuine cover-system legs or an actually-moving soldier
            // keep the squad unsettled.
            if (navWalking && !coverWalking) {
                Vec3 pos = soldier.position();
                double[] still = state.navStillPos.get(soldier.getUUID());
                int ticks = state.navStillTicks.getOrDefault(soldier.getUUID(), 0);
                // Half a block between observe samples: a real walk covers
                // ~2.5 blocks per 10t sample, while a docked soldier whose
                // cover re-search re-paths him in place jitters well under
                // this — the tight 0.1 threshold never accumulated samples
                // against that shuffle and the settle blocked forever.
                if (still != null
                    && (pos.x - still[0]) * (pos.x - still[0])
                        + (pos.z - still[2]) * (pos.z - still[2]) > 0.25D) {
                    ticks = 0;
                }
                state.navStillPos.put(soldier.getUUID(),
                    new double[] {pos.x, pos.y, pos.z});
                ticks++;
                state.navStillTicks.put(soldier.getUUID(), ticks);
                if (ticks < NAV_ZOMBIE_SETTLE_SAMPLES) {
                    allSettled = false;
                }
            } else {
                state.navStillPos.remove(soldier.getUUID());
                state.navStillTicks.remove(soldier.getUUID());
                // Plain navigation counts as movement too: the cover states
                // and ExposedCombatController do not own every leg (fallback
                // advances, the SECURE assemble walk ride raw navigation),
                // and a soldier mid-walk is not settled however calm his
                // cover state reads.
                if (coverWalking || navWalking) {
                    allSettled = false;
                }
            }
            cx += at.getX();
            cz += at.getZ();
        }
        cx /= squad.size();
        cz /= squad.size();

        // The settle interval must be CONTINUOUSLY quiet: soldiers moving,
        // seeking, or repositioning at any sample restarts the clock, so a
        // squad that wandered through the whole window cannot complete by
        // standing still for the final tick.
        if (areaHealth && allInside && allSettled) {
            if (state.settledSinceTick < 0) {
                state.settledSinceTick = now;
            }
        } else {
            state.settledSinceTick = -1;
        }

        boolean settled = state.settledSinceTick >= 0
            && now - state.settledSinceTick >= StevesArmyConfig.getAttackClearSettleTicks();

        if (settled) {
            state.stage = ClearingStage.SECURED;
            state.stalled = false;
            if (!state.completeRequested) {
                state.completeRequested = true;
                state.completedAtTick = now;
                completeOrder(server, state, activity);
            }
            return;
        }

        // TEMP Wave-A diagnostics: a squad parked inside a healthy area that
        // still cannot settle is the completion deadlock family — name the
        // blocker per soldier, rate-limited to one line per 200t.
        if (areaHealth && allInside) {
            Long last = NO_SETTLE_DIAG_LAST.get(state);
            if (last == null || now - last >= 200) {
                NO_SETTLE_DIAG_LAST.put(state, now);
                StringBuilder blockers = new StringBuilder();
                for (SoldierEntity soldier : squad) {
                    CoverBehaviorManager.CoverState cs = soldier.getCoverBehaviorManager().getState();
                    boolean moving = cs == CoverBehaviorManager.CoverState.SEEKING_COVER
                        || cs == CoverBehaviorManager.CoverState.REPOSITIONING
                        || ExposedCombatController.hasActiveMove(soldier)
                        || !soldier.getNavigation().isDone();
                    if (moving) {
                        blockers.append(' ').append(soldier.getStringUUID(), 0, 8)
                            .append('=').append(cs)
                            .append("/move=").append(ExposedCombatController.hasActiveMove(soldier))
                            .append("/nav=").append(!soldier.getNavigation().isDone());
                    }
                }
                StevesArmyMod.LOGGER.info("[AttackClear][diag] no-settle squad {} area={} inside={} "
                    + "settledSince={} blockers:{}", activity.objective(), areaHealth, allInside,
                    state.settledSinceTick,
                    blockers.isEmpty() ? "none(static)" : blockers.toString());
            }
        }

        // SECURE means the squad is inside a healthy space; on approach the
        // job is still CLEAR (advance) even when nothing is left to check.
        state.stage = areaHealth && allInside ? ClearingStage.SECURE : ClearingStage.CLEAR;

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
        boolean stallEligible = state.stage == ClearingStage.CLEAR
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

    private static void completeOrder(MinecraftServer server, ClearingState state,
                                      SquadActivityManager.AttackActivityView activity) {
        SquadActivityManager.completeAttackToHold(activity.ownerId(), activity.fireTeam(),
            activity.objective());
        ServerPlayer owner = server.getPlayerList().getPlayer(activity.ownerId());
        if (owner != null) {
            owner.sendSystemMessage(Component.literal("[Squad] Area secured."));
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
        return state != null && state.areaCells.contains(pos.asLong());
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
        boolean inside = state.areaCells.contains(soldier.blockPosition().asLong());

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
     * resolved, only physical completion (all-inside settle) remains. This is
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
        NO_SETTLE_DIAG_LAST.clear();
    }

    /** TEMP Wave-A diagnostic: per-state rate-limit clock for the no-settle diag. */
    private static final java.util.IdentityHashMap<ClearingState, Long> NO_SETTLE_DIAG_LAST =
        new java.util.IdentityHashMap<>();

    private static ClearingState stateFor(UUID ownerId, FireTeam team) {
        EnumMap<FireTeam, ClearingState> byTeam = STATES.get(ownerId);
        return byTeam == null ? null : byTeam.get(team);
    }
}
