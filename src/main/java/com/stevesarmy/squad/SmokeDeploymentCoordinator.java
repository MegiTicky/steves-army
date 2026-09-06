package com.stevesarmy.squad;

import com.stevesarmy.StevesArmyConfig;
import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.combat.GrenadeIntegration;
import com.stevesarmy.debug.DiagnosticLogManager;
import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.entity.ai.GrenadeTacticalController;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Fireteam-level trigger for smoke deployment during attacks. When a fireteam
 * stays heavily suppressed on an attack objective for long enough, the member
 * best able to throw (lowest personal suppression, carrying a smoke grenade)
 * lobs a LesRaisins smoke grenade at the threat position. Additional grenades
 * of a screen are thrown staggered on later ticks so the burst does not look
 * coordinated. While the screen is up, CoverTacticalGoal lets the team advance
 * through it despite the heavy-suppression hold.
 */
public final class SmokeDeploymentCoordinator {
    private static final long CLAIM_LEASE_TICKS = 60;
    private static final long ATTEMPT_RETRY_TICKS = 40;
    private static final long GATE_LOG_INTERVAL_TICKS = 100;
    private static final double SMOKE_SCREEN_SPREAD = 4.0;
    /** Minimum horizontal distance from the team centroid to the aim point. */
    private static final double MIN_SCREEN_FROM_TEAM = 8.0;
    private static final int BURST_GAP_MIN_TICKS = 10;
    private static final int BURST_GAP_RANGE_TICKS = 21;
    private static final long BURST_DEADLINE_TICKS = 200;
    private static final long PRUNE_INTERVAL_CALLS = 512;
    private static final long PRUNE_IDLE_TICKS = 24000;

    private static final class Entry {
        long screenUntilTick = Long.MIN_VALUE;
        long lastDeployTick = Long.MIN_VALUE;
        long lastAttemptTick = Long.MIN_VALUE;
        long lastLogTick = Long.MIN_VALUE;
        String lastRejectionSignature;
        long lastRejectionLogTick = Long.MIN_VALUE;
        @Nullable UUID claimedBy;
        long claimExpiresTick = Long.MIN_VALUE;
        @Nullable Vec3 screenPoint;
        // Staggered burst state: grenades 2..N of one screen, thrown on later
        // ticks relative to the frozen first-grenade geometry.
        int burstRemaining;
        int burstNextIndex;
        long burstNextTick = Long.MIN_VALUE;
        long burstDeadlineTick = Long.MIN_VALUE;
        @Nullable Vec3 burstBasePoint;
        @Nullable Vec3 burstPerp;
    }

    private static final Map<UUID, EnumMap<FireTeam, Entry>> DATA = new HashMap<>();
    private static long pruneCounter;

    private SmokeDeploymentCoordinator() {}

    /**
     * Called from CoverTacticalGoal while an attacking fireteam is pinned.
     * Enforces one thrower and one screen per fireteam via a short claim lease
     * and a fireteam deploy cooldown.
     */
    public static void maybeDeploySmoke(SoldierEntity soldier) {
        if (soldier.level().isClientSide) return;
        if (!StevesArmyConfig.isSmokeDeploymentEnabled()) return;
        if (!GrenadeIntegration.isAvailable()) return;
        UUID ownerId = soldier.getOwnerUUID().orElse(null);
        if (ownerId == null) return;
        FireTeam team = soldier.getFireTeam();
        if (team == FireTeam.GARRISON || team == FireTeam.ALL) return;

        long now = soldier.level().getGameTime();
        Entry entry = entry(ownerId, team);

        // A pending staggered burst finishes regardless of the gates below:
        // the first grenade authorized this screen, and a suppression dip
        // mid-burst must not orphan the rest of it with LOS gaps.
        if (entry.burstRemaining > 0 && entry.burstBasePoint != null) {
            continueBurst(soldier, ownerId, team, entry, now);
            return;
        }
        entry.burstBasePoint = null;
        entry.burstPerp = null;

        if (!FireTeamSuppressionTracker.isHeavilySuppressed(soldier)) {
            logGate(soldier, entry, now, String.format("fireteam not HEAVY (level=%.2f)",
                FireTeamSuppressionTracker.getLevel(soldier)));
            return;
        }
        long holdTicks = FireTeamSuppressionTracker.getHeavyHoldTicks(soldier);
        long requiredHold = StevesArmyConfig.getSmokeTriggerHoldTicks();
        if (holdTicks < requiredHold) {
            logGate(soldier, entry, now, "HEAVY_HOLD " + holdTicks + "/" + requiredHold);
            return;
        }
        if (soldier.getAttackTargetPos() == null) {
            logGate(soldier, entry, now, "no attack objective");
            return;
        }

        if (isScreenActive(entry, now)) return;
        if (entry.lastDeployTick != Long.MIN_VALUE
            && now - entry.lastDeployTick < StevesArmyConfig.getSmokeFireteamCooldownTicks()) {
            logGate(soldier, entry, now, "fireteam cooldown, "
                + (StevesArmyConfig.getSmokeFireteamCooldownTicks()
                    - (now - entry.lastDeployTick)) + " ticks remaining");
            return;
        }
        if (entry.lastAttemptTick != Long.MIN_VALUE
            && now - entry.lastAttemptTick < ATTEMPT_RETRY_TICKS) return;
        if (entry.claimedBy != null && !entry.claimedBy.equals(soldier.getUUID())
            && now < entry.claimExpiresTick) {
            logGate(soldier, entry, now, "another member holds the throw claim");
            return;
        }
        prune(now);

        ThrowerSelection selection = selectThrowers(soldier, ownerId, team, now);
        if (selection.candidates().isEmpty()) {
            entry.lastAttemptTick = now;
            log(soldier, "no usable thrower (" + selection.diagnosis() + ")");
            return;
        }

        ScreenGeometry geometry = resolveScreenGeometry(soldier);
        if (geometry == null) {
            entry.lastAttemptTick = now;
            log(soldier, "screen point unavailable (no forward threat, or threat/objective "
                + "too close to the team for a safe screen)");
            return;
        }

        // Claim before attempting so concurrent team members in the same tick
        // don't duplicate the throw; released if grenade 1 cannot be placed.
        entry.claimedBy = soldier.getUUID();
        entry.claimExpiresTick = now + CLAIM_LEASE_TICKS;

        ThrowAttempt first = attemptGrenade(selection, geometry, 0);
        if (!first.accepted()) {
            entry.claimedBy = null;
            entry.lastAttemptTick = now;
            logRejection(soldier, entry, now, first.summary());
            return;
        }

        int requested = StevesArmyConfig.getSmokeGrenadesPerScreen();
        entry.lastDeployTick = now;
        entry.screenUntilTick = now + StevesArmyConfig.getSmokeScreenDurationTicks();
        entry.screenPoint = first.point();
        String suffix = "";
        if (requested > 1) {
            entry.burstRemaining = requested - 1;
            entry.burstNextIndex = 1;
            entry.burstBasePoint = geometry.basePoint();
            entry.burstPerp = geometry.perp();
            entry.burstNextTick = now + burstGap(soldier);
            entry.burstDeadlineTick = now + BURST_DEADLINE_TICKS;
            suffix = ", " + (requested - 1) + " more staggered";
        }
        log(soldier, "deployed smoke screen (1/" + requested + " grenade(s)) at "
            + formatPoint(first.point()) + suffix);
    }

    private record ThrowAttempt(boolean accepted, @Nullable Vec3 point, String summary) {}

    /**
     * One grenade slot, offered to ranked candidates in order until one
     * accepts. A thrower who just accepted is PREPARING and cannot take a
     * second request the same tick, so grenades naturally spread members.
     */
    private static ThrowAttempt attemptGrenade(ThrowerSelection selection,
                                               ScreenGeometry geometry, int grenadeIndex) {
        List<String> rejections = new ArrayList<>();
        for (SoldierEntity thrower : selection.candidates()) {
            Vec3 point = pointForThrower(thrower, geometry.basePoint(), geometry.perp(),
                lateralOffset(grenadeIndex));
            if (point == null) {
                rejections.add(thrower.getId() + ": screen point out of reach");
                continue;
            }
            GrenadeTacticalController.SmokeRequestResult result =
                thrower.getGrenadeTacticalController().requestSmokeScreen(point);
            if (result.accepted()) {
                return new ThrowAttempt(true, point, "ok");
            }
            rejections.add(thrower.getId() + ": " + result.reason());
        }
        return new ThrowAttempt(false, null, "grenade " + grenadeIndex + " rejected by all "
            + selection.candidates().size() + " candidate(s): " + String.join("; ", rejections));
    }

    /** Throws the next staggered grenade of a running screen burst. */
    private static void continueBurst(SoldierEntity soldier, UUID ownerId, FireTeam team,
                                      Entry entry, long now) {
        if (now < entry.burstNextTick) return;
        if (now > entry.burstDeadlineTick) {
            log(soldier, "staggered burst ended with " + entry.burstRemaining
                + " grenade(s) unthrown (window expired)");
            clearBurst(entry);
            return;
        }
        ThrowerSelection selection = selectThrowers(soldier, ownerId, team, now);
        if (selection.candidates().isEmpty()) {
            // Every carrier is busy right now; wait for a freer tick.
            entry.burstNextTick = now + 20;
            return;
        }
        ScreenGeometry geometry = new ScreenGeometry(entry.burstBasePoint, entry.burstPerp);
        ThrowAttempt attempt = attemptGrenade(selection, geometry, entry.burstNextIndex);
        if (attempt.accepted()) {
            entry.screenPoint = attempt.point();
            log(soldier, "smoke burst grenade "
                + Math.min(entry.burstNextIndex + 1, StevesArmyConfig.getSmokeGrenadesPerScreen())
                + "/" + StevesArmyConfig.getSmokeGrenadesPerScreen() + " at "
                + formatPoint(attempt.point()));
        } else {
            logRejection(soldier, entry, now, attempt.summary());
        }
        // A rejected grenade slot is dropped rather than retried; the arc
        // geometry rarely changes fast enough to matter within the window.
        entry.burstRemaining--;
        entry.burstNextIndex++;
        entry.burstNextTick = now + burstGap(soldier);
        if (entry.burstRemaining <= 0) clearBurst(entry);
    }

    private static void clearBurst(Entry entry) {
        entry.burstRemaining = 0;
        entry.burstNextIndex = 0;
        entry.burstBasePoint = null;
        entry.burstPerp = null;
        entry.burstNextTick = Long.MIN_VALUE;
        entry.burstDeadlineTick = Long.MIN_VALUE;
    }

    private static int burstGap(SoldierEntity soldier) {
        return BURST_GAP_MIN_TICKS + soldier.getRandom().nextInt(BURST_GAP_RANGE_TICKS);
    }

    /** Aim point and perpendicular direction, frozen once per screen burst. */
    private record ScreenGeometry(Vec3 basePoint, Vec3 perp) {}

    /**
     * Picks the screen aim point: the forward-most known threat relative to
     * the attack objective direction, falling back to the objective itself.
     * Threats behind the team are ignored (no backwards throws), and the
     * result must clear the team by the cloud radius so the screen never
     * engulfs friendly soldiers.
     */
    @Nullable
    private static ScreenGeometry resolveScreenGeometry(SoldierEntity anyMember) {
        BlockPos attackPos = anyMember.getAttackTargetPos();
        if (attackPos == null) return null;
        Vec3 centroid = FireTeamSuppressionTracker.getCentroid(anyMember);
        if (centroid == null) centroid = anyMember.position();
        Vec3 centroidFlat = new Vec3(centroid.x, 0.0, centroid.z);
        Vec3 objective = new Vec3(attackPos.getX() + 0.5, 0.0, attackPos.getZ() + 0.5);
        Vec3 attackDir = objective.subtract(centroidFlat);
        if (attackDir.length() < 1.0) return null;
        attackDir = attackDir.normalize();

        Vec3 base = null;
        SquadThreatIntel intel = anyMember.getGrenadeSquadIntel();
        if (intel != null) {
            SquadThreatIntel.ThreatKnowledge assigned = intel
                .getAssignedThreatForSoldier(anyMember.getUUID()).orElse(null);
            if (isUsableThreat(anyMember, assigned)
                && forwardness(assigned, centroidFlat, attackDir) >= 0.0) {
                base = threatFlatPosition(assigned);
            } else {
                SquadThreatIntel.ThreatKnowledge best = null;
                for (SquadThreatIntel.ThreatKnowledge threat : intel.getAllThreats()) {
                    if (!isUsableThreat(anyMember, threat)) continue;
                    if (forwardness(threat, centroidFlat, attackDir) < 0.0) continue;
                    if (best == null || threat.accuracy > best.accuracy) best = threat;
                }
                if (best != null) base = threatFlatPosition(best);
            }
        }
        if (base == null) {
            // Objective fallback only when the cloud cannot reach back to the
            // team from there.
            if (objective.distanceTo(centroidFlat) < MIN_SCREEN_FROM_TEAM) return null;
            base = objective;
        }
        Vec3 toBase = base.subtract(centroidFlat);
        if (toBase.length() < MIN_SCREEN_FROM_TEAM) return null;
        Vec3 perp = new Vec3(-toBase.z, 0.0, toBase.x).normalize();
        return new ScreenGeometry(base, perp);
    }

    private static double forwardness(SquadThreatIntel.ThreatKnowledge threat,
                                      Vec3 centroidFlat, Vec3 attackDir) {
        return threatFlatPosition(threat).subtract(centroidFlat).dot(attackDir);
    }

    /** How far the thrower would have to place this grenade slot's screen point. */
    @Nullable
    private static Vec3 pointForThrower(SoldierEntity thrower, Vec3 basePoint, Vec3 perp,
                                        double lateralOffset) {
        Vec3 desired = basePoint.add(perp.scale(lateralOffset));
        Vec3 toPoint = new Vec3(desired.x - thrower.getX(), 0.0, desired.z - thrower.getZ());
        double throwDistance = toPoint.length();
        if (throwDistance < 1.0) return null;
        double maxRange = StevesArmyConfig.getGrenadeMaxRange();
        if (throwDistance > maxRange) {
            desired = new Vec3(thrower.getX() + toPoint.x * maxRange / throwDistance, 0.0,
                thrower.getZ() + toPoint.z * maxRange / throwDistance);
        }
        if (!(thrower.level() instanceof ServerLevel level)) return null;
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING,
            Mth.floor(desired.x), Mth.floor(desired.z));
        return new Vec3(desired.x, y + 0.5, desired.z);
    }

    /** Perpendicular offsets 0, +1, -1, +2, -2... times the spread, in blocks. */
    private static double lateralOffset(int grenadeIndex) {
        if (grenadeIndex == 0) return 0.0;
        int side = grenadeIndex % 2 == 1 ? 1 : -1;
        int steps = (grenadeIndex + 1) / 2;
        return side * steps * SMOKE_SCREEN_SPREAD;
    }

    /** True while a recently thrown smoke screen justifies an advance. */
    public static boolean isScreenActive(SoldierEntity soldier) {
        UUID ownerId = soldier.getOwnerUUID().orElse(null);
        if (ownerId == null) return false;
        return isScreenActive(ownerId, soldier.getFireTeam(), soldier.level().getGameTime());
    }

    /** Pure-data screen query for systems without a soldier handle (e.g. the
     * fireteam suppression tracker's per-fireteam recovery boost). */
    public static boolean isScreenActive(UUID ownerId, FireTeam team, long now) {
        if (team == FireTeam.GARRISON || team == FireTeam.ALL) return false;
        EnumMap<FireTeam, Entry> map = DATA.get(ownerId);
        if (map == null) return false;
        Entry entry = map.get(team);
        return entry != null && isScreenActive(entry, now);
    }

    @Nullable
    public static Vec3 getScreenPoint(SoldierEntity soldier) {
        UUID ownerId = soldier.getOwnerUUID().orElse(null);
        if (ownerId == null) return null;
        EnumMap<FireTeam, Entry> map = DATA.get(ownerId);
        if (map == null) return null;
        Entry entry = map.get(soldier.getFireTeam());
        return entry == null ? null : entry.screenPoint;
    }

    private static boolean isScreenActive(Entry entry, long now) {
        return entry.screenUntilTick != Long.MIN_VALUE && now < entry.screenUntilTick;
    }

    private static Entry entry(UUID ownerId, FireTeam team) {
        return DATA.computeIfAbsent(ownerId, k -> new EnumMap<>(FireTeam.class))
            .computeIfAbsent(team, k -> new Entry());
    }

    private static void prune(long now) {
        if (++pruneCounter < PRUNE_INTERVAL_CALLS) return;
        pruneCounter = 0;
        for (EnumMap<FireTeam, Entry> map : DATA.values()) {
            map.values().removeIf(entry ->
                !isScreenActive(entry, now)
                && entry.burstRemaining <= 0
                && (entry.claimedBy == null || now >= entry.claimExpiresTick)
                && Math.max(entry.lastDeployTick, entry.lastAttemptTick) + PRUNE_IDLE_TICKS < now);
        }
        DATA.values().removeIf(Map::isEmpty);
    }

    private record ThrowerSelection(List<SoldierEntity> candidates, String diagnosis) {}

    /**
     * Ranks living smoke carriers by personal suppression (least suppressed
     * first, deterministic UUID tie-break). Mirrors the frag pipeline's own
     * tolerances: a soldier queued in the grenade controller's arc pipeline or
     * on the frag personal cooldown remains eligible, because smoke requests
     * take over WAITING_FOR_ARC and smoke pacing is owned by this coordinator.
     */
    private static ThrowerSelection selectThrowers(SoldierEntity anyMember, UUID ownerId,
                                                   FireTeam team, long now) {
        MinecraftServer server = anyMember.level().getServer();
        ServerPlayer player = server.getPlayerList().getPlayer(ownerId);
        if (player == null) return new ThrowerSelection(List.of(), "owner player offline");
        FireTeamAssignment fta;
        try {
            fta = FireTeamAssignment.get((ServerLevel) player.level(), ownerId);
        } catch (Exception e) {
            return new ThrowerSelection(List.of(), "fireteam assignment unavailable");
        }
        List<SoldierEntity> ranked = new ArrayList<>();
        Map<String, Integer> excluded = new LinkedHashMap<>();
        int alive = 0;
        int carriers = 0;
        for (UUID memberId : fta.getSoldiersInTeam(team)) {
            Entity memberEntity = ((ServerLevel) anyMember.level()).getEntity(memberId);
            if (!(memberEntity instanceof SoldierEntity soldier)) continue;
            if (!soldier.isAlive() || soldier.isRemoved()) continue;
            if (soldier.getFireTeam() != team) continue;
            alive++;
            if (GrenadeIntegration.findSmokeSlot(soldier.getSoldierInventory()) < 0) continue;
            carriers++;
            String blocked = smokeAvailabilityBlocker(soldier);
            if (blocked != null) {
                excluded.merge(blocked, 1, Integer::sum);
                continue;
            }
            ranked.add(soldier);
        }
        ranked.sort((a, b) -> {
            float sa = personalSuppression(a);
            float sb = personalSuppression(b);
            if (sa != sb) return Float.compare(sa, sb);
            return a.getUUID().compareTo(b.getUUID());
        });
        if (!ranked.isEmpty()) {
            return new ThrowerSelection(ranked, "alive=" + alive + ", carriers=" + carriers
                + ", available=" + ranked.size());
        }
        if (alive == 0) return new ThrowerSelection(List.of(), "no living team members found");
        if (carriers == 0) {
            return new ThrowerSelection(List.of(), alive + " member(s) alive, none carries a smoke "
                + "grenade in general inventory (expected lrtactical:throwable with "
                + "ThrowableId=lrtactical:smoke_grenade)");
        }
        return new ThrowerSelection(List.of(), carriers + " carry smoke but all excluded: "
            + describeExclusions(excluded));
    }

    @Nullable
    private static String smokeAvailabilityBlocker(SoldierEntity soldier) {
        if (soldier.isHealing()) return "healing";
        if (soldier.isPassenger()) return "passenger";
        if (soldier.isNavigationTraversalLocked()) return "nav-locked";
        if (soldier.getGrenadeTacticalController().isSmokeRequestBlocked()) {
            return "frag-throw-in-progress";
        }
        return null;
    }

    private static float personalSuppression(SoldierEntity soldier) {
        return soldier.getCoverBehaviorManager()
            .getSuppressionTracker().getSuppressionLevel();
    }

    private static String describeExclusions(Map<String, Integer> excluded) {
        if (excluded.isEmpty()) return "unknown reason";
        StringBuilder result = new StringBuilder();
        for (Map.Entry<String, Integer> e : excluded.entrySet()) {
            if (result.length() > 0) result.append(", ");
            result.append(e.getKey()).append('=').append(e.getValue());
        }
        return result.toString();
    }

    private static boolean isUsableThreat(SoldierEntity soldier,
                                          @Nullable SquadThreatIntel.ThreatKnowledge threat) {
        // A screen only needs the enemy's rough position, so a much older
        // memory is acceptable than for frag targeting (120 ticks): after a
        // long pin the fresh intel is gone exactly when smoke matters most.
        return threat != null && threat.isAlive && threat.lastKnownPosition != null
            && soldier.level().getGameTime() - threat.lastSeenTime <= 600;
    }

    @Nullable
    private static Vec3 threatFlatPosition(SquadThreatIntel.ThreatKnowledge threat) {
        Vec3 position = null;
        if (threat.lastVisibleHeadPoint != null) position = threat.lastVisibleHeadPoint;
        else if (threat.lastVisibleAimPoint != null) position = threat.lastVisibleAimPoint;
        else if (threat.lastKnownPosition != null) {
            position = new Vec3(threat.lastKnownPosition.getX() + 0.5,
                threat.lastKnownPosition.getY() + 0.25, threat.lastKnownPosition.getZ() + 0.5);
        }
        return position == null ? null : new Vec3(position.x, 0.0, position.z);
    }

    private static String formatPoint(@Nullable Vec3 point) {
        return point == null ? "none" : String.format("(%.1f, %.1f, %.1f)",
            point.x, point.y, point.z);
    }

    /** Attempt results; gated behind grenade debug logging like other grenade diagnostics. */
    private static void log(SoldierEntity soldier, String message) {
        if (!DiagnosticLogManager.isGrenadeLoggingEnabled()) return;
        StevesArmyMod.LOGGER.info("[SmokeDeploy] soldier={} team={} {}",
            soldier.getId(), soldier.getFireTeam(), message);
    }

    /**
     * Total-failure results, deduplicated: the same rejection repeating every
     * retry tick (arc geometry rarely changes) is logged once per 100 ticks.
     */
    private static void logRejection(SoldierEntity soldier, Entry entry, long now,
                                     String message) {
        if (!DiagnosticLogManager.isGrenadeLoggingEnabled()) return;
        if (message.equals(entry.lastRejectionSignature)
            && entry.lastRejectionLogTick != Long.MIN_VALUE
            && now - entry.lastRejectionLogTick < GATE_LOG_INTERVAL_TICKS) {
            return;
        }
        entry.lastRejectionSignature = message;
        entry.lastRejectionLogTick = now;
        log(soldier, message);
    }

    /**
     * Why the fireteam is waiting rather than throwing; same debug gating as
     * {@link #log}, throttled per fireteam so a long pin doesn't spam the log.
     */
    private static void logGate(SoldierEntity soldier, Entry entry, long now, String gate) {
        if (!DiagnosticLogManager.isGrenadeLoggingEnabled()) return;
        if (entry.lastLogTick != Long.MIN_VALUE
            && now - entry.lastLogTick < GATE_LOG_INTERVAL_TICKS) return;
        entry.lastLogTick = now;
        StevesArmyMod.LOGGER.info("[SmokeDeploy] soldier={} team={} waiting: {}",
            soldier.getId(), soldier.getFireTeam(), gate);
    }
}
