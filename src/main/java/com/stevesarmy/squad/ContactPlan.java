package com.stevesarmy.squad;

import com.stevesarmy.StevesArmyConfig;
import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.combat.VisibilityRay;
import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.entity.SoldierRole;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Squad close-contact doctrine (contact-plan rework, wave A): an ATTACK
 * clearing POI is a TASK, not a movement permission. Before this plan the
 * clearing order told every soldier where progress was needed and left the
 * "may I walk there" decision to each of them — so the first soldier to draw
 * the short straw walked alone into an enemy's lane and died, one at a time,
 * on the same corner. The fix is one shared per-squad CLOSE-CONTACT record:
 * while the squad knows of exactly one live threat at close range, advancing
 * toward it is gated by THIS class, and the gate is hard.
 *
 * <p>The gate has two halves. SUPPORT: somebody else must be carrying the
 * fight — a squadmate with a confirmed clear firing lane onto the threat's
 * last-known position, or one that actually fired recently (the same
 * real-evidence rule as {@link SquadMovementCoordinator}'s covering fire —
 * a flag that says "someone is covering" is worthless, muzzle flash is not).
 * GEOMETRY: even with support, the move must not drive INTO the threat —
 * either it cuts an angle wide of the danger arc, or it does not close the
 * distance. Suppression without displacement just feeds the enemy a second
 * soldier.
 *
 * <p>Hard gates that never open kill squads in the other direction, so three
 * EMERGENCIES bypass support entirely: the enemy is close enough to overrun
 * the line (CQB range — geometry is already lost), a squadmate right here is
 * suppressed or has just died (the fight is on top of the squad; waiting is
 * the bigger risk), or the clearing order itself has latched STALLED (the
 * squad is already blocked — a stricter gate on top of a stuck order only
 * guarantees the stall). Emergencies are deliberately cheap checks placed
 * before the ray budget: they are the paths a dying squad must still be able
 * to take.
 *
 * <p>At most ONE contact is open per squad at a time, anchored to the
 * freshest live squad-intel threat within coordination range of the squad
 * centroid. Roles on the contact (base of fire, checker) are reassigned the
 * moment their holder dies or loses LoS — a stale base-of-fire assignment is
 * exactly the "covering fire" that exists only on paper.
 *
 * <p>State is static per squad (same pattern as
 * {@link SquadMovementCoordinator}) and cleared by {@link #resetAll()}
 * between sim scenarios. Server-tick clock everywhere: soldier.tickCount is
 * each entity's own age, and mixing the two breaks every comparison between
 * soldiers spawned at different times. The one exception is
 * {@code lastSightingTick}, which stores the threat intel's own game-time
 * stamp so contact freshness compares intel to intel — never across clocks.
 *
 * <p>Intended observe cadence: the squad's 10-tick heavy observe pass (the
 * same cadence ClearingCoordinator uses for {@code observe} /
 * {@code updateContactPoints}), so role and range decisions lag reality by
 * at most one observe pass and the per-tick cost of the gate itself is map
 * lookups plus arithmetic.
 */
public final class ContactPlan {

    /**
     * Support-ray budget per gate evaluation: the 4 mates nearest the mover
     * are ray-tested for a clear lane onto the threat. A squad is at most 8,
     * and the nearest mates are the ones whose lane actually covers the
     * mover's route; rays beyond the budget buy telemetry, not safety.
     */
    private static final int SUPPORT_RAY_BUDGET = 4;
    /**
     * Casualty emergency window: a squadmate death counts as "the fight is
     * here" for this long after the plan first observed the soldier gone.
     * 100 ticks is five seconds — long enough to cover the remainder of the
     * firefight that killed him, short enough that a minutes-old corpse in
     * another room never opens a CQB lane.
     */
    private static final int CASUALTY_WINDOW_TICKS = 100;
    /**
     * Emergency proximity: suppressed or just-killed squadmates only open
     * the lane for soldiers within this range of them. A death across the
     * map is grief, not an emergency here.
     */
    private static final double EMERGENCY_MATE_RANGE_SQ = 8.0 * 8.0;
    /**
     * Last-known migration hysteresis, matching ClearingCoordinator's
     * contact-point rule: small drift inside this radius does not move the
     * anchor. Roles are validated against the STORED position — evidence
     * (a clear lane) is only meaningful at the position it was gathered for.
     */
    private static final double KNOWN_MIGRATION_DIST_SQ = 16.0;

    /** squadId -> the squad's single open close-contact plan. */
    private static final Map<UUID, CloseContact> CONTACTS = new HashMap<>();
    /**
     * squadId -> memberId -> first-observed-gone record. Death recency is
     * approximated here because nothing else tracks "soldier died at tick T"
     * in a readable form; the plan samples membership on its observe cadence,
     * so a recorded death is accurate to within one observe pass.
     */
    private static final Map<UUID, Map<UUID, LostMate>> LOST_MATES = new HashMap<>();
    /** squadId -> memberId -> last observed feet position of living members. */
    private static final Map<UUID, Map<UUID, BlockPos>> MEMBER_POSITIONS = new HashMap<>();
    /**
     * squadId -> server tick of the most recent contact OPEN. Outlives the
     * contact itself: a contact that opens and resolves between two observers
     * still happened, and ordering asserts ("SECURED only after the contact
     * opened") must see it. Re-opens overwrite; first-open ordering reads use
     * min-observed semantics on the caller side.
     */
    private static final Map<UUID, Long> LAST_OPEN_TICK = new HashMap<>();

    private ContactPlan() {}

    // ------------------------------------------------------------------ state

    /**
     * The squad's one open close-contact plan. Fields are public and mutable
     * (ThreatKnowledge style) because the coordinator refreshes them in
     * place; only {@link ContactPlan} constructs one.
     */
    public static final class CloseContact {
        public UUID threatId;
        /** Stored anchor of the contact; migrates only past the hysteresis. */
        public BlockPos lastKnown;
        /** Intel clock (game time) of the freshest sighting of the threat. */
        public long lastSightingTick;
        /** Server tick the contact was opened (role/tenure math). */
        public long openedTick;
        /** Soldier holding the firing lane onto the threat (null = unassigned). */
        public UUID baseOfFireId;
        /** Nearest healthy soldier not on the gun (null = unassigned). */
        public UUID checkerId;
        /**
         * Last server tick the squad showed combat on this contact: a member's
         * successful shot or a pinned member. The bloodless-standoff escape
         * ages against this clock — sightings alone are not combat.
         */
        public long lastCombatTick;

        CloseContact(UUID threatId, BlockPos lastKnown, long lastSightingTick, long openedTick) {
            this.threatId = threatId;
            this.lastKnown = lastKnown;
            this.lastSightingTick = lastSightingTick;
            this.openedTick = openedTick;
            this.lastCombatTick = openedTick;
        }

        /** Aim point every LoS test and gate computation shares. */
        public Vec3 threatCenter() {
            return Vec3.atCenterOf(lastKnown);
        }
    }

    /** One observed squadmate death: when it was first noticed, where he fell. */
    private static final class LostMate {
        long deathTick;
        BlockPos lastSeenPos;
    }

    /** The advance verdict. The reason is a stable short token for sim telemetry. */
    public static final class MovePermission {
        public final boolean allowed;
        public final String reason;

        private MovePermission(boolean allowed, String reason) {
            this.allowed = allowed;
            this.reason = reason;
        }

        public static MovePermission allow(String reason) {
            return new MovePermission(true, reason);
        }

        public static MovePermission deny(String reason) {
            return new MovePermission(false, reason);
        }

        @Override
        public String toString() {
            return (allowed ? "ALLOW:" : "DENY:") + reason;
        }
    }

    // ------------------------------------------------------------------ tick

    /**
     * Observe-cadence maintenance: open/refresh/close the squad's single
     * contact and keep its roles truthful. Called once per squad per heavy
     * observe pass; everything cheap is left to {@link #checkAdvance}.
     */
    public static void tick(ServerLevel level, SquadData squad) {
        if (!StevesArmyConfig.isSquadContactPlanEnabled()) {
            // Same rule as ClearingCoordinator: a disabled plan must not keep
            // gating anybody through stale state — clear it, don't pause it.
            CONTACTS.clear();
            LOST_MATES.clear();
            MEMBER_POSITIONS.clear();
            return;
        }
        if (level == null || squad == null) {
            return;
        }
        MinecraftServer server = level.getServer();
        if (server == null) {
            return;
        }
        long now = server.getTickCount();
        UUID squadId = squad.getSquadId();

        List<SoldierEntity> members = livingMembers(level, squad);
        updateCasualtyRecords(squadId, squad, members, now);

        if (members.isEmpty()) {
            // A squad with nobody alive holds no contact: the gate must die
            // with its squad, not linger to confuse a future re-formed one.
            CONTACTS.remove(squadId);
            return;
        }

        // Squad centroid: the coordination range is a SQUAD property — a
        // contact relevant to the group, not to whoever happens to be nearest.
        Vec3 centroid = Vec3.ZERO;
        for (SoldierEntity member : members) {
            centroid = centroid.add(member.position());
        }
        centroid = centroid.scale(1.0 / members.size());

        double range = StevesArmyConfig.getContactCoordinationRange();
        double rangeSq = range * range;

        // Freshest live in-range threat: lastKnownPosition is the only thing
        // the squad can act on (a sighting without a position is a noise
        // report), isAlive keeps resolved kills from reopening a contact.
        SquadThreatIntel.ThreatKnowledge freshest = null;
        for (SquadThreatIntel.ThreatKnowledge threat : squad.getThreatIntel().getAllThreats()) {
            if (!threat.isAlive || threat.lastKnownPosition == null) {
                continue;
            }
            if (centroid.distanceToSqr(Vec3.atCenterOf(threat.lastKnownPosition)) > rangeSq) {
                continue;
            }
            if (freshest == null || threat.lastSeenTime > freshest.lastSeenTime) {
                freshest = threat;
            }
        }

        CloseContact contact = CONTACTS.get(squadId);
        if (contact != null) {
            SquadThreatIntel.ThreatKnowledge known =
                squad.getThreatIntel().getThreat(contact.threatId).orElse(null);
            boolean close = false;
            if (known == null) {
                // Intel forgot the threat (its own retention rules ran out).
                // Forgetting is not "resolved", but a position this old is no
                // longer knowledge — keeping a hard gate anchored to a ghost
                // would freeze the squad forever with no emergency in sight.
                close = true;
            } else if (!known.isAlive) {
                // Death confirmed by intel is the one resolution that needs
                // no investigation (same rule as ClearingCoordinator).
                close = true;
            } else if (centroid.distanceToSqr(contact.threatCenter()) > rangeSq) {
                // The threat left the coordination band: this is no longer a
                // CLOSE contact the squad plans around jointly.
                close = true;
            }
            if (close) {
                CONTACTS.remove(squadId);
                contact = null;
            }
        }

        if (contact == null) {
            if (freshest == null) {
                return;
            }
            contact = new CloseContact(freshest.threatEntityId,
                freshest.lastKnownPosition.immutable(), freshest.lastSeenTime, now);
            CONTACTS.put(squadId, contact);
            LAST_OPEN_TICK.put(squadId, now);
            StevesArmyMod.LOGGER.info("[ContactPlan][diag] squad {} OPEN contact threat {} known {} at t{}",
                squadId, contact.threatId, contact.lastKnown.toShortString(), now);
        } else if (freshest != null && !freshest.threatEntityId.equals(contact.threatId)
            && freshest.lastSeenTime > contact.lastSightingTick) {
            // A DIFFERENT threat is now strictly fresher than anything we
            // have recorded on the current one: the current contact has gone
            // quiet while someone else started shooting. A quiet contact must
            // not pin the squad's gate while a live shooter is fresher — but
            // equal-staleness ties keep the incumbent, because target churn
            // resets roles mid-firefight for no gain.
            contact = new CloseContact(freshest.threatEntityId,
                freshest.lastKnownPosition.immutable(), freshest.lastSeenTime, now);
            CONTACTS.put(squadId, contact);
            LAST_OPEN_TICK.put(squadId, now);
            StevesArmyMod.LOGGER.info("[ContactPlan][diag] squad {} SWAP contact -> threat {} known {} at t{}",
                squadId, contact.threatId, contact.lastKnown.toShortString(), now);
        } else {
            // Same threat: refresh the sighting clock, migrate the anchor
            // only past the hysteresis (evidence gathered at the old corner
            // says nothing about the new one — migration re-arms the roles).
            contact.lastSightingTick = Math.max(contact.lastSightingTick, freshest.lastSeenTime);
            BlockPos known = freshest.lastKnownPosition.immutable();
            if (contact.lastKnown.distSqr(known) > KNOWN_MIGRATION_DIST_SQ) {
                contact.lastKnown = known;
                // The lane evidence was validated against the old anchor.
                contact.baseOfFireId = null;
                contact.checkerId = null;
            }
        }

        reassignRoles(level, contact, members);

        // Combat clock for the bloodless-standoff escape: only SHOTS age it.
        // Being shot AT deliberately does not — a squad pinned under fire it
        // cannot answer is stranded, not fighting, and letting defensive
        // suppression refresh the clock turns the escape into a deadlock:
        // the enemy that pins us best holds the gate shut forever. The
        // suppressed-mate rescue bypass above already covers the case where
        // a pinned mate needs help now; this escape covers the case where
        // nobody can fight at all.
        long staleShotBefore = now - StevesArmyConfig.getAttackClearStallTicks();
        for (SoldierEntity member : members) {
            Long shot = SquadMovementCoordinator.lastSuccessfulShotTick(member);
            if (shot != null && shot >= staleShotBefore) {
                contact.lastCombatTick = now;
                break;
            }
        }
    }

    /**
     * Role truthfulness. Runs every observe pass but only pays rays when a
     * role is actually vacant or suspect: the gate is fail-closed, so a
     * stale base-of-fire assignment is not a cosmetic bug — it is the exact
     * "support" that does not exist when the mover checks.
     */
    private static void reassignRoles(ServerLevel level, CloseContact contact,
                                      List<SoldierEntity> members) {
        Vec3 target = contact.threatCenter();

        SoldierEntity base = memberById(members, contact.baseOfFireId);
        if (base != null
            && !VisibilityRay.trace(level, base.getEyePosition(), target, base).clear()) {
            // The holder lost his lane: whatever pinned the enemy down a
            // moment ago pins nothing now. Demote and re-search.
            base = null;
            contact.baseOfFireId = null;
        }
        if (base == null) {
            contact.baseOfFireId = pickBaseOfFire(level, members, target);
        }

        SoldierEntity checker = memberById(members, contact.checkerId);
        if (checker == null
            || (contact.baseOfFireId != null && contact.checkerId.equals(contact.baseOfFireId))
            || checker.getCoverBehaviorManager().isSuppressed()) {
            // The checker needs no lane — he needs to be alive, well, and not
            // the same man on the gun. Suppressed men check nothing.
            contact.checkerId = pickChecker(contact, members);
        }
    }

    /**
     * Base of fire: a machine gunner with a clear lane is the doctrine answer
     * (his lane IS the covering fire), then anyone with a clear lane. Nearest
     * to the threat first within each class — the closest confirmed lane is
     * the one most likely to survive small threat moves. No confirmed lane at
     * all leaves the role vacant: the gate stays shut (fail closed) rather
     * than pretending a lane exists.
     */
    @Nullable
    private static UUID pickBaseOfFire(ServerLevel level,
                                       List<SoldierEntity> members, Vec3 target) {
        List<SoldierEntity> candidates = new ArrayList<>(members);
        candidates.sort(Comparator
            .comparingDouble((SoldierEntity m) -> m.position().distanceToSqr(target))
            .thenComparing(m -> m.getRole() != SoldierRole.MACHINE_GUNNER));
        int rays = 0;
        for (SoldierEntity candidate : candidates) {
            if (rays >= SUPPORT_RAY_BUDGET) {
                break;
            }
            rays++;
            if (VisibilityRay.trace(level, candidate.getEyePosition(), target, candidate).clear()) {
                return candidate.getUUID();
            }
        }
        return null;
    }

    /** Checker: nearest healthy soldier who is not the one on the gun. */
    @Nullable
    private static UUID pickChecker(CloseContact contact, List<SoldierEntity> members) {
        SoldierEntity best = null;
        double bestDist = Double.MAX_VALUE;
        for (SoldierEntity member : members) {
            if (contact.baseOfFireId != null && member.getUUID().equals(contact.baseOfFireId)) {
                continue;
            }
            if (member.getCoverBehaviorManager().isSuppressed()) {
                continue;
            }
            // Distance to the anchor, not to any particular soldier: the
            // checker's job is to look at where the threat was.
            double dist = member.distanceToSqr(contact.threatCenter());
            if (dist < bestDist) {
                bestDist = dist;
                best = member;
            }
        }
        return best == null ? null : best.getUUID();
    }

    // ------------------------------------------------------- casualty records

    /**
     * Samples membership to approximate "died recently". A member present in
     * last pass's position snapshot and absent (or dead) now is recorded as
     * fallen THIS tick at his last observed position. Sampling means the
     * recorded time can lag the real death by one observe pass — acceptable
     * against a 100-tick emergency window, and cheaper than a death hook
     * threaded through every removal path.
     */
    private static void updateCasualtyRecords(UUID squadId, SquadData squad,
                                              List<SoldierEntity> members, long now) {
        Map<UUID, LostMate> lost =
            LOST_MATES.computeIfAbsent(squadId, ignored -> new HashMap<>());
        Map<UUID, BlockPos> previous = MEMBER_POSITIONS.get(squadId);

        Set<UUID> roster = new HashSet<>(squad.getMemberIds());
        Set<UUID> livingNow = new HashSet<>();
        for (SoldierEntity member : members) {
            livingNow.add(member.getUUID());
        }
        if (previous != null) {
            for (Map.Entry<UUID, BlockPos> was : previous.entrySet()) {
                if (!livingNow.contains(was.getKey()) && roster.contains(was.getKey())
                    && !lost.containsKey(was.getKey())) {
                    LostMate record = new LostMate();
                    record.deathTick = now;
                    record.lastSeenPos = was.getValue();
                    lost.put(was.getKey(), record);
                }
            }
        }
        Map<UUID, BlockPos> positions = new HashMap<>();
        for (SoldierEntity member : members) {
            positions.put(member.getUUID(), member.blockPosition().immutable());
        }
        MEMBER_POSITIONS.put(squadId, positions);

        // Prune on both clocks: records older than double the window are
        // dead weight, and members no longer on the roster (left/despawned)
        // were never emergencies for this squad.
        lost.entrySet().removeIf(entry -> now - entry.getValue().deathTick > CASUALTY_WINDOW_TICKS * 2
            || !roster.contains(entry.getKey()));
    }

    // --------------------------------------------------------------- read side

    /** True while this soldier's squad has an open close-contact plan. */
    public static boolean hasActiveContact(@Nullable SoldierEntity soldier) {
        if (!StevesArmyConfig.isSquadContactPlanEnabled() || soldier == null) {
            return false;
        }
        UUID squadId = soldier.getSquadId();
        return squadId != null && CONTACTS.containsKey(squadId);
    }

    /**
     * The point nobody walks toward: the threat's last-known center, or null
     * with no active contact. Consumed by movement/cover code that needs to
     * know WHERE the danger is, not just that it exists.
     */
    @Nullable
    public static Vec3 dangerPoint(@Nullable SoldierEntity soldier) {
        if (!StevesArmyConfig.isSquadContactPlanEnabled() || soldier == null) {
            return null;
        }
        UUID squadId = soldier.getSquadId();
        CloseContact contact = squadId == null ? null : CONTACTS.get(squadId);
        return contact == null ? null : contact.threatCenter();
    }

    // ------------------------------------------------------------------- gate

    /**
     * THE HARD GATE. Every advance toward a close-contact threat passes
     * through here; a denial is not advice, the soldier does not move.
     * Order of rules is load-bearing: cheap emergencies first (a dying squad
     * must not wait behind a ray budget), support second, geometry last.
     */
    public static MovePermission checkAdvance(SoldierEntity soldier, BlockPos dest) {
        if (!StevesArmyConfig.isSquadContactPlanEnabled()) {
            return MovePermission.allow("contact plan disabled");
        }
        UUID squadId = soldier.getSquadId();
        CloseContact contact = squadId == null ? null : CONTACTS.get(squadId);
        if (contact == null) {
            return MovePermission.allow("no active contact");
        }
        if (!(soldier.level() instanceof ServerLevel level)) {
            // The gate is a server decision; a client-side caller has no
            // authority to deny movement anyway.
            return MovePermission.allow("client side");
        }
        MinecraftServer server = level.getServer();
        if (server == null) {
            return MovePermission.allow("no server clock");
        }
        long now = server.getTickCount();
        Vec3 threat = contact.threatCenter();

        // (b) EMERGENCY: overrun range. At CQB distance the enemy can already
        // reach the squad; the choice is not "advance vs hold", it is "fight
        // now vs die stacked up". Geometry arguments are moot here.
        double overrunSq = StevesArmyConfig.getCqbOverrunRange();
        overrunSq *= overrunSq;
        if (soldier.distanceToSqr(threat) <= overrunSq) {
            return MovePermission.allow("cqb overrun range");
        }

        // (c) EMERGENCY: the fight is on top of the squad. A nearby suppressed
        // mate means the enemy's attention is FIXED elsewhere — the safest
        // moment to move is exactly now. A mate who just died means the enemy
        // is killing the squad in place; refusing to close then only spaces
        // the deaths out. Proximity (8 blocks) keeps a far-away casualty from
        // opening a lane it has nothing to do with.
        Map<UUID, LostMate> lost = LOST_MATES.getOrDefault(squadId, Map.of());
        for (UUID memberId : squadMembers(level, squadId)) {
            if (memberId.equals(soldier.getUUID())) {
                continue;
            }
            if (!(level.getEntity(memberId) instanceof SoldierEntity mate)) {
                continue;
            }
            if (!mate.isAlive()) {
                continue;
            }
            if (mate.distanceToSqr(soldier.position()) > EMERGENCY_MATE_RANGE_SQ) {
                continue;
            }
            if (mate.getCoverBehaviorManager().isSuppressed()) {
                return MovePermission.allow("mate suppressed");
            }
            LostMate record = lost.get(memberId);
            if (record != null && now - record.deathTick <= CASUALTY_WINDOW_TICKS
                && record.lastSeenPos != null
                && soldier.blockPosition().distSqr(record.lastSeenPos) <= EMERGENCY_MATE_RANGE_SQ) {
                return MovePermission.allow("mate down");
            }
        }

        // (d) EMERGENCY: the clearing order itself reports STALLED. The stall
        // latch already means the squad cannot make safe progress; a harder
        // gate on a stuck order converts "blocked" into "blocked forever".
        if (ClearingCoordinator.isStalledLatched(soldier)) {
            gateDiag(soldier, "clearing stalled");
            return MovePermission.allow("clearing stalled");
        }

        // (d2) EMERGENCY: bloodless standoff. A contact the squad has held
        // for a full stall window without a shot on either side and without
        // anyone pinned is a guard, not a firefight — no amount of waiting
        // produces the covering fire the support leg demands, and the order's
        // checks still need walking. The advance proceeds under the geometry
        // leg being consciously waived too: the flank the geometry wants is
        // often a wall away, and a 30-second mutual stare with nobody firing
        // is the field sign that the squad should just go clear the room.
        // Fresh-sighted ARMED contacts never sit this quiet (their fire is
        // the support evidence), so this only opens the watched-quiet case.
        if (now - contact.lastCombatTick >= StevesArmyConfig.getAttackClearStallTicks()) {
            gateDiag(soldier, "bloodless standoff");
            return MovePermission.allow("bloodless standoff");
        }

        // (e) SUPPORT + GEOMETRY.
        List<SoldierEntity> mates = new ArrayList<>();
        for (UUID memberId : squadMembers(level, squadId)) {
            if (memberId.equals(soldier.getUUID())) {
                continue;
            }
            if (level.getEntity(memberId) instanceof SoldierEntity mate && mate.isAlive()) {
                mates.add(mate);
            }
        }

        // Real covering fire: an actual successful shot inside the cover
        // window. Same evidence standard as SquadMovementCoordinator — a
        // flag nobody fired under is not support.
        boolean coveringFire = false;
        int window = StevesArmyConfig.getSquadBoundCoverWindowTicks();
        for (SoldierEntity mate : mates) {
            Long lastShot = SquadMovementCoordinator.lastSuccessfulShotTick(mate);
            if (lastShot != null && now - lastShot <= window) {
                coveringFire = true;
                break;
            }
        }

        // A confirmed clear lane onto the threat from any squadmate. Nearest
        // mates first (their lanes cover the mover's route), capped at the
        // budget — this is the per-advance ray cost ceiling.
        boolean laneOnTarget = false;
        if (!coveringFire) {
            List<SoldierEntity> byNearest = new ArrayList<>(mates);
            byNearest.sort(Comparator.comparingDouble(m -> m.distanceToSqr(soldier.position())));
            int rays = 0;
            for (SoldierEntity mate : byNearest) {
                if (rays >= SUPPORT_RAY_BUDGET) {
                    break;
                }
                rays++;
                if (VisibilityRay.trace(level, mate.getEyePosition(), threat, mate).clear()) {
                    laneOnTarget = true;
                    break;
                }
            }
        }
        if (!coveringFire && !laneOnTarget) {
            gateDiag(soldier, "no support");
            return MovePermission.deny("no support");
        }

        // Geometry: with someone carrying the fight, the mover must still not
        // walk INTO it. Either the move cuts wide of the danger arc around
        // the threat bearing, or it does not close the distance at all.
        Vec3 from = soldier.position();
        Vec3 toDest = Vec3.atCenterOf(dest);
        Vec3 move = toDest.subtract(from);
        Vec3 toEnemy = threat.subtract(from);
        double moveLen = move.length();
        double enemyLen = toEnemy.length();
        boolean wideOfArc = true;
        if (moveLen > 1.0E-4 && enemyLen > 1.0E-4) {
            // Degenerate vectors carry no heading; the distance rule alone
            // decides them (a zero-length move closes nothing).
            double cos = move.dot(toEnemy) / (moveLen * enemyLen);
            double angleDeg = Math.toDegrees(Math.acos(Mth.clamp(cos, -1.0, 1.0)));
            wideOfArc = angleDeg >= StevesArmyConfig.getContactDangerArcDegrees();
        }
        boolean notCloser = toDest.distanceToSqr(threat) >= from.distanceToSqr(threat);
        if (!wideOfArc && !notCloser) {
            gateDiag(soldier, "danger arc");
            return MovePermission.deny("danger arc");
        }

        gateDiag(soldier, "supported advance");
        return MovePermission.allow("supported advance");
    }

    /**
     * When this soldier's squad last OPENED a contact (history — survives a
     * contact that already closed), or MAX_VALUE if none ever opened. Sim and
     * ordering-assert view: "has a close contact happened at all" must stay
     * answerable after the contact resolved.
     */
    public static long contactOpenTick(SoldierEntity soldier) {
        UUID squadId = soldier.getSquadId();
        if (squadId == null) {
            return Long.MAX_VALUE;
        }
        CloseContact contact = CONTACTS.get(squadId);
        if (contact != null) {
            return contact.openedTick;
        }
        Long last = LAST_OPEN_TICK.get(squadId);
        return last == null ? Long.MAX_VALUE : last;
    }

    /**
     * TEMP Wave-A diagnostics: the gate verdicts, rate-limited to one line per
     * soldier per 100t. Promoting to WARN telemetry (or removing) is Wave B's
     * call, once the arbitration owns a real decision surface.
     */
    private static final java.util.Map<UUID, Long> GATE_DIAG_LAST = new java.util.HashMap<>();

    private static void gateDiag(SoldierEntity soldier, String verdict) {
        if (!(soldier.level() instanceof ServerLevel level)) {
            return;
        }
        long now = level.getServer() == null ? 0 : level.getServer().getTickCount();
        Long last = GATE_DIAG_LAST.get(soldier.getUUID());
        if (last != null && now - last < 100) {
            return;
        }
        GATE_DIAG_LAST.put(soldier.getUUID(), now);
        StevesArmyMod.LOGGER.info("[ContactPlan][gate] {} {} at {}", soldier.getStringUUID(),
            verdict, now);
    }

    // ------------------------------------------------------------------ reset

    /** Sim-harness isolation hook. */
    public static void resetAll() {
        CONTACTS.clear();
        LOST_MATES.clear();
        MEMBER_POSITIONS.clear();
        LAST_OPEN_TICK.clear();
        GATE_DIAG_LAST.clear();
    }

    // ------------------------------------------------------------------ helpers

    /** Living squad members resolved from the roster (authoritative, not a scan). */
    private static List<SoldierEntity> livingMembers(ServerLevel level, SquadData squad) {
        List<SoldierEntity> members = new ArrayList<>();
        for (UUID memberId : squad.getMemberIds()) {
            if (level.getEntity(memberId) instanceof SoldierEntity soldier
                && soldier.isAlive() && !soldier.isRemoved()) {
                members.add(soldier);
            }
        }
        return members;
    }

    private static List<UUID> squadMembers(ServerLevel level, UUID squadId) {
        return SquadManager.get(level).getSquadById(squadId)
            .map(SquadData::getMemberIds)
            .orElse(List.of());
    }

    @Nullable
    private static SoldierEntity memberById(List<SoldierEntity> members, @Nullable UUID id) {
        if (id == null) {
            return null;
        }
        for (SoldierEntity member : members) {
            if (member.getUUID().equals(id)) {
                return member;
            }
        }
        return null;
    }
}
