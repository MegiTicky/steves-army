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
 * ping (walls and floors bound it, so the order never silently claims rooms
 * through walls). Blind spots — wall-adjacent area cells and the last-known
 * positions of enemies that broke contact — become check targets. Soldiers
 * claim one each; observation (proximity plus a budgeted visibility-ray pass
 * over real line of sight, never proximity through walls) marks them checked.
 * The order completes as "Area secured" only when the squad has reached the
 * space, checked its blind spots, resolved known contact, and settled — never
 * on proximity alone. A stalled attack is reported once and then persists
 * until the player replaces or cancels it.
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
                               int checkedCount, int totalCount) {
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

        /** Enemies that broke contact inside the area halo: last-known position is a check target. */
        Map<UUID, BlockPos> contactPoints = new HashMap<>();

        Map<UUID, BlockPos> claims = new HashMap<>();

        ClearingStage stage = ClearingStage.APPROACH;
        boolean stalled;
        long lastProgressTick;
        long settledSinceTick = -1;
        long stallAnnouncedTick = Long.MIN_VALUE;
        /** Squad centroid when the current stall window began (net-displacement anchor). */
        Vec3 progressCentroid = null;
        long completedAtTick = Long.MIN_VALUE;
        boolean completeRequested;
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

            List<SoldierEntity> squad = livingRecipients(state, activity);
            if (squad.isEmpty()) {
                continue;
            }

            long gameTime = state.level.getGameTime();
            refreshObjectiveClocks(squad);
            if (gameTime % HEAVY_INTERVAL_TICKS == 0) {
                observe(state, squad);
                updateContactPoints(state, squad);
                updateStageAndCompletion(server, state, activity, squad);
            }
            if (gameTime % CLAIM_REFRESH_TICKS == 0) {
                assignClaims(state, squad);
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
        state.contactPoints.clear();
        state.claims.clear();
        state.stage = ClearingStage.APPROACH;
        state.stalled = false;
        state.settledSinceTick = -1;
        state.stallAnnouncedTick = Long.MIN_VALUE;
        state.progressCentroid = null;
        state.completeRequested = false;
        state.completedAtTick = Long.MIN_VALUE;
        state.level = findLevel(server, state);
        if (state.level == null) {
            state.areaCells = Set.of();
            state.pois = List.of();
            return;
        }
        computeArea(state);
        state.lastProgressTick = state.level.getGameTime();
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

        // Being somewhere reveals it: a soldier standing inside the cleared
        // space has its adjacent corners in view, no ray needed. Outside the
        // space proximity proves nothing - walls conceal corners (a soldier
        // hugging the outside of a sealed room must not check its interior).
        for (SoldierEntity soldier : squad) {
            BlockPos at = soldier.blockPosition();
            if (!state.areaCells.contains(at.asLong())) {
                continue;
            }
            for (BlockPos poi : state.pois) {
                if (poi.distSqr(at) <= 8) {
                    check(state, poi, now);
                }
            }
            for (BlockPos contact : state.contactPoints.values()) {
                if (contact.distSqr(at) <= 8) {
                    check(state, contact, now);
                }
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
                    check(state, target, now);
                    break;
                }
                if (spent >= budget) {
                    break;
                }
            }
        }
    }

    private static void check(ClearingState state, BlockPos target, long now) {
        if (state.checkedTick.putIfAbsent(target.asLong(), now) == null) {
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
        state.lastProgressTick = now;
        state.stalled = false;
        state.stallAnnouncedTick = Long.MIN_VALUE;
    }

    // --------------------------------------------------------------- contact

    /**
     * Enemies that broke contact inside the area halo: their last-known
     * position becomes a check target. Losing sight of an enemy neither ends
     * the attack nor grants perfect knowledge of where he went — the squad
     * must look before the order can complete.
     */
    private static void updateContactPoints(ClearingState state, List<SoldierEntity> squad) {
        int haloRadius = (int) (StevesArmyConfig.getAttackClearRadius()
            + StevesArmyConfig.getAttackClearAwareness() * 0.5);
        long haloSq = (long) haloRadius * haloRadius;

        state.squadIds.clear();
        for (SoldierEntity soldier : squad) {
            if (soldier.getSquadId() != null) {
                state.squadIds.add(soldier.getSquadId());
            }
        }

        Set<UUID> liveContacts = new HashSet<>();
        for (UUID squadId : state.squadIds) {
            SquadManager.get(state.level).getSquadById(squadId).ifPresent(squadData -> {
                for (SquadThreatIntel.ThreatKnowledge threat : squadData.getThreatIntel().getAllThreats()) {
                    if (!threat.isAlive || threat.lastKnownPosition == null) {
                        continue;
                    }
                    if (threat.lastKnownPosition.distSqr(state.objective) > haloSq) {
                        continue;
                    }
                    liveContacts.add(threat.threatEntityId);
                    BlockPos known = threat.lastKnownPosition.immutable();
                    BlockPos existing = state.contactPoints.get(threat.threatEntityId);
                    if (existing == null || existing.distSqr(known) > 16) {
                        state.contactPoints.put(threat.threatEntityId, known);
                    }
                }
            });
        }
        state.contactPoints.keySet().removeIf(id -> !liveContacts.contains(id));
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
        // Checked last-known position AND out of recent contact: the squad
        // looked where the enemy went and he was not there.
        Long checkedAt = state.checkedTick.get(contact.getValue().asLong());
        return checkedAt != null && now - lastSeen(state, contact.getKey()) > RECENT_CONTACT_TICKS;
    }

    private static long lastSeen(ClearingState state, UUID threatId) {
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

    private static void assignClaims(ClearingState state, List<SoldierEntity> squad) {
        Set<UUID> living = new HashSet<>();
        for (SoldierEntity soldier : squad) {
            living.add(soldier.getUUID());
        }
        state.claims.keySet().removeIf(id -> !living.contains(id));
        state.claims.entrySet()
            .removeIf(claim -> state.checkedTick.containsKey(claim.getValue().asLong()));

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

        long zoneSq = (long) Math.pow(StevesArmyConfig.getAttackClearRadius() + 4, 2);
        boolean allInside = true;
        boolean allSettled = true;
        double cx = 0;
        double cz = 0;
        for (SoldierEntity soldier : squad) {
            BlockPos at = soldier.blockPosition();
            if (!inCompletionZone(state, at, zoneSq)) {
                allInside = false;
            }
            CoverBehaviorManager.CoverState coverState = soldier.getCoverBehaviorManager().getState();
            if (coverState == CoverBehaviorManager.CoverState.SEEKING_COVER
                || coverState == CoverBehaviorManager.CoverState.REPOSITIONING
                || ExposedCombatController.hasActiveMove(soldier)) {
                allSettled = false;
            }
            cx += at.getX();
            cz += at.getZ();
        }
        cx /= squad.size();
        cz /= squad.size();

        if (areaHealth && allInside) {
            if (state.settledSinceTick < 0) {
                state.settledSinceTick = now;
            }
        } else {
            state.settledSinceTick = -1;
        }

        boolean settled = state.settledSinceTick >= 0
            && now - state.settledSinceTick >= StevesArmyConfig.getAttackClearSettleTicks();

        if (settled && allSettled) {
            state.stage = ClearingStage.SECURED;
            state.stalled = false;
            if (!state.completeRequested) {
                state.completeRequested = true;
                state.completedAtTick = now;
                completeOrder(server, state, activity);
            }
            return;
        }

        // SECURE means the squad is inside a healthy space; on approach the
        // job is still CLEAR (advance) even when nothing is left to check.
        state.stage = areaHealth && allInside ? ClearingStage.SECURE : ClearingStage.CLEAR;

        Vec3 centroid = new Vec3(cx, 0, cz);
        // Stall: no check, no resolution, and no net displacement across the
        // full window. Maneuvering re-anchors the window - a squad still
        // working the problem is not stuck; a squad planted at a wall it
        // cannot pass is. Reported once; the order persists until replaced.
        if (state.progressCentroid == null) {
            state.progressCentroid = centroid;
        }
        long sinceProgress = now - state.lastProgressTick;
        boolean netMoved = centroid.distanceToSqr(state.progressCentroid) > 2.25;
        if (netMoved) {
            state.progressCentroid = centroid;
        }
        if (state.stage == ClearingStage.CLEAR
            && sinceProgress >= StevesArmyConfig.getAttackClearStallTicks()
            && !netMoved) {
            state.stalled = true;
            state.stage = ClearingStage.STALLED;
            if (state.stallAnnouncedTick == Long.MIN_VALUE) {
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

    private static boolean inCompletionZone(ClearingState state, BlockPos pos, long zoneSq) {
        if (state.areaCells.contains(pos.asLong())) {
            return true;
        }
        if (state.objective.distSqr(pos) > zoneSq) {
            return false;
        }
        // Near an area cell counts: secure watch positions sit on the perimeter.
        for (Long packed : state.areaCells) {
            if (BlockPos.of(packed).distSqr(pos) <= 9) {
                return true;
            }
        }
        return false;
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

        return new ClearingView(stage, assignedPoi, poiChecked,
            nearestUncheckedFor(state, soldier), inside,
            state.completeRequested, countChecked(state),
            state.pois.size() + state.contactPoints.size());
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
        return state == null ? -1 : state.stage.ordinal();
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

    public static void clearAll() {
        STATES.clear();
    }

    private static ClearingState stateFor(UUID ownerId, FireTeam team) {
        EnumMap<FireTeam, ClearingState> byTeam = STATES.get(ownerId);
        return byTeam == null ? null : byTeam.get(team);
    }
}
